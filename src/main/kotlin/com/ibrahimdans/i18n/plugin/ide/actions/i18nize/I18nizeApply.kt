package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Localization
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.factory.CallTemplate
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleTemplateResolver
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.parser.KeyParserBuilder
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.LocaleMatching
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.ibrahimdans.i18n.plugin.utils.hasRecognizedLocale
import com.ibrahimdans.i18n.plugin.utils.hostVirtualFile
import com.ibrahimdans.i18n.plugin.utils.isLocaleNamedFile
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager

/**
 * Writes an accepted [I18nizePlan]: the values in the translation files, then the calls in the code —
 * every file in one command, so a single Ctrl+Z undoes it all, code included.
 *
 * Two steps, because finding the translation files walks the file-type index, which the platform
 * forbids on the EDT, while writing must happen there:
 * - [prepare], in a read action, in smart mode, off the EDT: decides where each value goes — the
 *   file of its namespace in its locale, or the file to create when there is none;
 * - [write], on the EDT: writes, without any dialog.
 *
 * A value never replaces another: a key the file already holds keeps its value, and a key whose path
 * runs into a value (`menu` is a text, the key is `menu.open`) is not written. A text whose reference
 * file can neither be found nor created — no module template, no `{lang}/{ns}` folder to follow — is
 * left as it is: its value, and its call in the code, would point at nothing.
 */
internal class I18nizeApply private constructor(
    private val project: Project?,
    private val files: List<FileWork>,
    /** The texts left as they are, for lack of a reference file. */
    val skipped: List<I18nizePlan.Entry>
) {

    /** What [write] did: the calls written in the code, the translation files created, the values kept. */
    data class Outcome(val replaced: Int, val createdFiles: List<String>, val keptValues: Int)

    private class FileWork(val plan: I18nizePlan.FilePlan, val entries: List<EntryWork>)

    private class EntryWork(val entry: I18nizePlan.Entry, val fullKey: FullKey, val values: List<ValueWork>)

    private data class ValueWork(val target: Target, val value: String)

    /** Where a value goes: a file found, or one to create at [path] under [base]. */
    private sealed interface Target {
        data class Existing(val source: LocalizationSource) : Target
        data class Missing(val base: VirtualFile, val path: String, val localization: Localization<PsiElement>) : Target
    }

    /**
     * Writes the values, creates the missing files, replaces the texts by their calls and gives the
     * React components that need it their `useTranslation` hook — in one command named [commandName].
     * A text changed since [prepare] (its element gone) is left alone, value included.
     */
    fun write(commandName: String = PluginBundle.message("action.batch.extract.title")): Outcome {
        var replaced = 0
        var kept = 0
        val created = mutableListOf<String>()
        val project = project ?: return Outcome(0, emptyList(), 0)
        val codeFiles = files.map { it.plan.file }.filter { it.isValid }
        WriteCommandAction.writeCommandAction(project, *codeFiles.toTypedArray())
            .withName(commandName)
            .withGlobalUndo()
            .withUndoConfirmationPolicy(UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION)
            .run<RuntimeException> {
                val documents = PsiDocumentManager.getInstance(project)
                documents.commitAllDocuments()
                // Computed on the code as it is, before anything moves; applied last.
                val code = files.mapNotNull { codeEdits(project, it) }
                val createdSources = mutableMapOf<String, LocalizationSource>()
                code.flatMap { it.entries }.forEach { entry ->
                    entry.values.forEach values@{ (target, value) ->
                        val source = when (target) {
                            is Target.Existing -> target.source
                            is Target.Missing -> {
                                val id = target.base.url + "/" + target.path
                                createdSources[id] ?: run {
                                    val (source, fresh) = create(project, target, entry.fullKey, value) ?: return@values
                                    createdSources[id] = source
                                    // A file just created holds the value it was created for.
                                    if (fresh) {
                                        created += target.path
                                        return@values
                                    }
                                    source
                                }
                            }
                        }
                        if (!writeValue(source, entry.fullKey, value)) kept++
                    }
                }
                code.forEach { edits ->
                    apply(edits.document, edits.edits)
                    documents.commitDocument(edits.document)
                    replaced += edits.entries.size
                }
            }
        return Outcome(replaced, created, kept)
    }

    private class FileEdits(val document: Document, val entries: List<EntryWork>, val edits: List<CodeEdit>)

    /** The calls replacing [work]'s texts, and the hooks they need; null when the file is gone. */
    private fun codeEdits(project: Project, work: FileWork): FileEdits? {
        val file = work.plan.file
        if (!file.isValid) return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val extractors = I18nizeSources.extractors()
        val entries = work.entries.filter { it.entry.candidate.literal.isValid }
        val hooks = linkedMapOf<JSFunction, String>()
        val calls = entries.mapNotNull { entryWork ->
            val literal = entryWork.entry.candidate.literal
            val extractor = extractors.firstOrNull { it.canExtract(literal) } ?: return@mapNotNull null
            var call = CallTemplate.template(extractor, literal)("'${entryWork.entry.key}'")
            HookInsertion.componentNeedingHook(literal)?.let { component ->
                hooks.putIfAbsent(component, entryWork.entry.namespace)
                call = HookInsertion.callThroughHook(call)
            }
            CodeEdit(extractor.textRange(literal), call)
        }
        // The literals of one message share a range (`'Hello ' + name`): replaced once.
        return FileEdits(document, entries, calls.distinctBy { it.range } + HookInsertion.edits(file, hooks))
    }

    /**
     * Applies [edits], all computed on the same text, from the end of the document backwards so
     * that none shifts another. At one offset the replacement goes first and an insertion second,
     * which puts the inserted text before it — `{ const { t } = …; return ` before the call.
     */
    private fun apply(document: Document, edits: List<CodeEdit>) {
        edits.sortedWith(compareByDescending<CodeEdit> { it.range.startOffset }.thenByDescending { it.range.endOffset })
            .forEach { document.replaceString(it.range.startOffset, it.range.endOffset, it.text) }
    }

    /**
     * Creates [target] holding [fullKey] = [value], with true; the file itself, with false, when it
     * appeared since [prepare]; null when its folder cannot be made.
     */
    private fun create(project: Project, target: Target.Missing, fullKey: FullKey, value: String): Pair<LocalizationSource, Boolean>? {
        val folderPath = target.path.substringBeforeLast('/', "")
        val folder = (if (folderPath.isEmpty()) target.base else VfsUtil.createDirectoryIfMissing(target.base, folderPath))
            ?.let { PsiManager.getInstance(project).findDirectory(it) } ?: return null
        val name = target.path.substringAfterLast('/')
        val existing = folder.findFile(name)
        val generator = target.localization.contentGenerator()
        val file = existing ?: folder.add(
            PsiFileFactory.getInstance(project).createFileFromText(name, generator.getLanguage(), generator.generateContent(fullKey, value))
        ).containingFile
        return LocalizationSource(target.localization.elementsTree(file), name, folder.name, target.path, target.localization) to (existing == null)
    }

    /**
     * Writes [fullKey] = [value] into [source] the way *Create key* does; false when nothing is
     * written: the key is there already — its value is kept — or its path runs into a value.
     */
    private fun writeValue(source: LocalizationSource, fullKey: FullKey, value: String): Boolean {
        val reference = Resolver.resolveCompositeKey(fullKey.compositeKey, source) ?: return false
        val element = reference.element ?: return false
        if (reference.unresolved.isEmpty() || !element.isTree()) return false
        val generator = source.localization.contentGenerator()
        if (!generator.isSuitable(element.value())) return false
        generator.generate(element.value(), fullKey, reference.unresolved, value)
        return true
    }

    private object Resolver : CompositeKeyResolver<PsiElement>

    companion object {

        /**
         * Decides where every value of [plan] goes: the text itself in the reference locale of its
         * file's module, and [translations] — locale → key, as [I18nizePlan.Entry.key] spells it →
         * value — in the other locales. Needs a read action, in smart mode, off the EDT.
         */
        fun prepare(plan: I18nizePlan, translations: Map<String, Map<String, String>> = emptyMap()): I18nizeApply {
            val project = plan.files.firstOrNull()?.file?.project ?: return I18nizeApply(null, emptyList(), emptyList())
            val config = Settings.getInstance(project).config()
            val parser = if (config.usesFlatKeys()) KeyParserBuilder.withoutTokenizer()
                         else KeyParserBuilder.withSeparators(config.nsSeparator, config.keySeparator)
            val service = project.service<LocalizationSourceService>()
            val skipped = mutableListOf<I18nizePlan.Entry>()

            val files = plan.files.map { filePlan ->
                val module = ownerOf(filePlan.file, config)
                val moduleSources = ModuleSources.sourcesOf(project, module).filter { it.hasRecognizedLocale() }
                val reference = ReferenceLocale.of(module, config, moduleSources.map { it.localeLabel() }.distinct())
                    ?: ReferenceLocale.wanted(module, config)
                val locator = Locator(project, config, module, moduleSources)

                FileWork(filePlan, filePlan.entries.mapNotNull { entry ->
                    val fullKey = parser.build().parse(
                        RawKey(listOf(KeyElement.literal(entry.key))),
                        emptyNamespace = config.usesFlatKeys(),
                        firstComponentNamespace = config.firstComponentNs
                    )
                    val sources = fullKey?.let { key ->
                        service.findSources(key.allNamespaces(), filePlan.file)
                            .filter { TranslationDataLoader.extractNamespace(it, config.defaultNamespaces().first()) == entry.namespace }
                    }
                    val referenceTarget = sources?.let { locator.target(it, reference, entry.namespace) }
                    if (fullKey == null || referenceTarget == null) {
                        skipped += entry
                        return@mapNotNull null
                    }
                    val others = translations
                        .filterKeys { LocaleMatching.pick(it, listOf(reference)) == null }
                        .mapNotNull { (locale, values) ->
                            val value = values[entry.key] ?: return@mapNotNull null
                            locator.target(sources, locale, entry.namespace)?.let { ValueWork(it, value) }
                        }
                    EntryWork(entry, fullKey, listOf(ValueWork(referenceTarget, entry.candidate.originalText)) + others)
                })
            }
            return I18nizeApply(project, files.filter { it.entries.isNotEmpty() }, skipped)
        }

        private fun ownerOf(element: PsiElement, config: Config): ModuleConfig? {
            if (config.modules.isEmpty()) return null
            val file = element.hostVirtualFile() ?: return null
            return ModuleSources.owner(config.modules, ModuleSources.FilePath.of(file, element.project.basePath ?: ""))
        }
    }

    /** Where a namespace's file of a locale is in one module, or would be created. */
    private class Locator(
        private val project: Project,
        private val config: Config,
        private val module: ModuleConfig?,
        private val moduleSources: List<LocalizationSource>
    ) {

        /** The file of [namespace] in [locale] among [sources], or the one to create; null when neither. */
        fun target(sources: List<LocalizationSource>, locale: String, namespace: String): Target? {
            val label = LocaleMatching.pick(locale, sources.map { it.localeLabel() }.distinct())
            sources.firstOrNull { label != null && it.localeLabel() == label }?.let { return Target.Existing(it) }
            return missing(locale, namespace)
        }

        /**
         * The file to create for [namespace] in [locale]: where the module's path template puts it,
         * else next to the files of the same layout — `<locale>/<namespace>` folders, or files named
         * after their locale for the default namespace. Null when nothing says where.
         */
        private fun missing(locale: String, namespace: String): Target.Missing? {
            val localization = localization()
            val extension = localization.contentGenerator().getType().defaultExtension
            val isDefault = namespace in config.defaultNamespaces()
            fromTemplate(locale, namespace, isDefault, extension)?.let { (base, path) -> return Target.Missing(base, path, localization) }

            moduleSources.forEach { source ->
                val folder = fileOf(source)?.parent ?: return@forEach
                if (source.isLocaleNamedFile()) {
                    if (isDefault) return Target.Missing(folder, "$locale.$extension", localization)
                } else if (folder.name == source.localeLabel()) {
                    val base = folder.parent ?: return@forEach
                    return Target.Missing(base, "$locale/$namespace.$extension", localization)
                }
            }
            return null
        }

        private fun fromTemplate(locale: String, namespace: String, isDefault: Boolean, extension: String): Pair<VirtualFile, String>? {
            val module = module ?: return null
            if (module.pathTemplate.isBlank() && module.fileTemplate.isBlank()) return null
            val template = ModuleTemplateResolver.combine(module)
            if (ModuleTemplateResolver.issues(template).isNotEmpty()) return null
            // A template without a namespace designates the default one's file only.
            if (!isDefault && NAMESPACE.findAll(template).none()) return null
            var path = ModuleTemplateResolver.resolve(template, locale, namespace)
            if (!path.substringAfterLast('/').contains('.')) path += ".$extension"
            val base = if (path.startsWith("/")) LocalFileSystem.getInstance().findFileByPath("/") else project.guessProjectDir()
            return base?.let { it to path.trimStart('/') }
        }

        /** The format of the module's files, else the preferred one, else JSON. */
        @Suppress("UNCHECKED_CAST")
        private fun localization(): Localization<PsiElement> {
            moduleSources.firstOrNull()?.let { return it.localization }
            val all = Extensions.LOCALIZATION.extensionList
            return all.firstOrNull { it.config().id() == config.preferredLocalization }
                ?: all.first { it.config().id() == "json" }
        }

        private fun fileOf(source: LocalizationSource): VirtualFile? =
            (source.tree?.value() ?: source.host)?.containingFile?.virtualFile

        private companion object {
            val NAMESPACE = Regex("""\{\s*(?i:ns|namespace)\s*}""")
        }
    }
}
