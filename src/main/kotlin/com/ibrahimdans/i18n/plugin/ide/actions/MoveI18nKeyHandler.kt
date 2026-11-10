package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Localization
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.TranslationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nReference
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleTemplateResolver
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.review.ReviewMigration
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.ibrahimdans.i18n.plugin.utils.deletePropertyAndSeparator
import com.ibrahimdans.i18n.plugin.utils.of
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.search.searches.ReferencesSearch
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Moves an i18n key from its current namespace to another namespace.
 *
 * For a key like `t('common:user.name')` — or `t('user.name')` with `useTranslation('common')` —
 * the action:
 *  1. Resolves the key's value leaves in every locale file of the source namespace
 *  2. Shows a combo to pick the target namespace (e.g. "profile")
 *  3. Copies each value to the target namespace's file of the same locale, creating it if missing
 *     (see [targetSourceFor] for where that file is)
 *  4. Deletes the entry from the source files
 *  5. Rewrites all code references with an explicit target namespace in front of the key
 *
 * The whole operation runs under a single WriteCommandAction (one undo step), the reviews of the
 * moved key included: they follow it to the target namespace ([ReviewMigration]), approval kept.
 * Where each value goes is decided before, under a read action ([planTargets]): finding the target
 * reads the source index, which a write action would hold the EDT for on a large project.
 *
 * The source namespace is the one of the *resolved* translation sources (not the literal text), so
 * keys whose namespace comes from a `useTranslation('ns')` hook are handled correctly. It is read
 * as the tool window reads it ([TranslationDataLoader.extractNamespace]): the namespace a module
 * template or a format states for the file (`locales/{ns}/{lang}.json`, `errors_fr.properties`),
 * the file name otherwise. The file stem alone offered `messages_fr` as a namespace to move from.
 *
 * Keys are spelled with the configured separators, both when a reference is told apart from an
 * unrelated literal and when it is rewritten: with `keySeparator = "/"` the references were
 * compared against a `.`-joined path and matched nothing, and the moved key was written with `:`
 * and `.`, which the parser would not read back.
 */
class MoveI18nKeyHandler : AnAction(), CompositeKeyResolver<PsiElement> {

    /**
     * The i18n key resolved at the caret, collected under a read action before any UI/write.
     *
     * @param leavesByNamespace the resolved value PSI nodes grouped by source namespace; more than one
     *        entry means the same key resolves in several namespaces (e.g. a `useTranslation(['a','b'])`
     *        hook where both files define the key) and the user must pick which one to move.
     */
    data class ResolvedKey(
        val sourceCodeElement: PsiElement,
        val compositeKey: List<Literal>,
        val leavesByNamespace: Map<String, List<PsiElement>>,
    )

    /** One text replacement in a code file, read before the PSI changes and applied after. */
    private data class Rewrite(val document: Document, val range: TextRange, val text: String)

    /**
     * Where a moved value goes, decided under a read action before the write ([targetSourceFor]):
     * the write action only creates the file when missing and writes into it.
     */
    internal sealed interface MoveTarget {
        /** A source of the target namespace and locale the project already has. */
        class Existing(val source: LocalizationSource) : MoveTarget

        /** The file [name] in [folder] under [anchor], created — folder included — when missing. */
        class File(
            val anchor: VirtualFile,
            val folder: String,
            val name: String,
            val localization: Localization<PsiElement>,
        ) : MoveTarget
    }

    /**
     * BGT: [update] reads `PSI_FILE` and resolves the references under the caret, which the
     * platform refuses on the EDT ("'psi.File' is requested on EDT") — and it runs every time the
     * editor context menu opens. On BGT the update gets read access; nothing in it needs Swing.
     * [actionPerformed] still runs on the EDT, the flag only concerns [update].
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible =
            editor != null && psiFile != null && findI18nReference(editor, psiFile) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val resolved = ReadAction.compute<ResolvedKey?, RuntimeException> { resolveKey(editor, psiFile) }
        if (resolved == null) {
            Messages.showErrorDialog(
                project,
                PluginBundle.message("action.move.error.unresolved"),
                PluginBundle.message("action.move.title")
            )
            return
        }

        val config = Settings.getInstance(project).config()
        val keyPath = spellPath(resolved.compositeKey, config)

        // When the key resolves in several namespaces (multi-namespace hook), ask which one to move.
        val sourceNs = if (resolved.leavesByNamespace.size > 1) {
            showNamespaceCombo(
                project,
                PluginBundle.message("action.move.source.prompt", keyPath),
                resolved.leavesByNamespace.keys.sorted()
            ) ?: return
        } else {
            // resolveKey guarantees leavesByNamespace is non-empty, but that invariant
            // lives in another function: fail closed rather than throw if it ever breaks.
            resolved.leavesByNamespace.keys.firstOrNull() ?: return
        }
        val sourceLeaves = resolved.leavesByNamespace[sourceNs] ?: return

        val namespaces = DialogViewModel(project).loadNamespaces().filter { it != sourceNs }
        if (namespaces.isEmpty()) {
            Messages.showErrorDialog(
                project,
                PluginBundle.message("action.move.error.no.namespace"),
                PluginBundle.message("action.move.title")
            )
            return
        }

        val targetNs = showNamespaceCombo(
            project, PluginBundle.message("action.move.target.prompt", sourceNs, keyPath), namespaces
        ) ?: return

        if (hasCollision(project, targetNs, resolved.compositeKey, resolved.sourceCodeElement, config)) {
            val choice = Messages.showYesNoDialog(
                project,
                PluginBundle.message("action.move.overwrite", keyPath, targetNs),
                PluginBundle.message("action.move.title"),
                Messages.getWarningIcon()
            )
            if (choice != Messages.YES) return
        }

        val (codeUsages, targets) = ReadAction.compute<Pair<List<PsiElement>, Map<PsiElement, MoveTarget>>, RuntimeException> {
            collectCodeUsages(sourceLeaves, resolved.sourceCodeElement) to planTargets(project, sourceLeaves, targetNs)
        }

        // Global and without confirmation: the review file it may rewrite has no editor.
        WriteCommandAction.writeCommandAction(project)
            .withName(PluginBundle.message("action.move.title"))
            .withGlobalUndo()
            .withUndoConfirmationPolicy(UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION)
            .run<RuntimeException> { execute(project, sourceLeaves, codeUsages, resolved.compositeKey, targetNs, targets) }
    }

    // --- Core (testable; must be called inside a write action) ---

    /**
     * Performs the move: copies each source leaf value into the target namespace's file of the same
     * locale (creating it when absent), deletes the source entries, and rewrites the code references.
     *
     * Pure of any UI; intended to be called from within a [WriteCommandAction]. [targets] comes from
     * [planTargets] under a read action beforehand; a leaf without a target is not copied. Left out,
     * the targets are planned here, inside the write action.
     */
    internal fun execute(
        project: Project,
        sourceLeaves: List<PsiElement>,
        codeUsages: List<PsiElement>,
        compositeKey: List<Literal>,
        targetNs: String,
        targets: Map<PsiElement, MoveTarget> = planTargets(project, sourceLeaves, targetNs),
    ) {
        val config = Settings.getInstance(project).config()

        // 0. Read before the PSI changes: where the reviews of the source entries live, and which
        //    code literals write the moved key — told by parsing them, which needs their key intact.
        val reviews = sourceLeaves.mapNotNull { ReviewMigration.moveOf(it, namespace = targetNs) }
        val rewrites = codeUsages.distinct().mapNotNull { rewriteOf(it, compositeKey, targetNs, config) }

        // 1. Copy into the target namespace, one file per source locale (created if missing).
        sourceLeaves.forEach { leaf ->
            targets[leaf]?.let { insertIntoTarget(project, leaf, it, compositeKey, targetNs, config) }
        }

        // 2. Remove the source entries, separating comma included: a bare delete()
        //    leaves `{,"sibling":…}` behind and corrupts the file whenever the moved
        //    key has a sibling — i.e. almost always in a real translation file.
        sourceLeaves.mapNotNull { toPropertyElement(it) }.forEach { deletePropertyAndSeparator(it) }

        // 3. Rewrite code references with an explicit target namespace in front of the key.
        rewrites.groupBy { it.document }.forEach { (document, edits) ->
            // Replace from the end so earlier offsets stay valid across multiple edits in one document.
            edits.sortedByDescending { it.range.startOffset }
                .forEach { document.replaceString(it.range.startOffset, it.range.endOffset, it.text) }
        }

        // 4. The reviews follow the key to its new namespace, in each locale.
        ReviewMigration.move(project, reviews)
    }

    // --- Read-phase collection ---

    /**
     * Where each of [sourceLeaves] goes in [targetNs] — see [targetSourceFor]. Read-only: reads the
     * source index and creates nothing, so it runs under a read action before the write.
     */
    internal fun planTargets(project: Project, sourceLeaves: List<PsiElement>, targetNs: String): Map<PsiElement, MoveTarget> {
        val config = Settings.getInstance(project).config()
        return sourceLeaves.mapNotNull { leaf ->
            val sourceFile = leaf.containingFile ?: return@mapNotNull null
            targetSourceFor(project, sourceFile, targetNs, config)?.let { leaf to it }
        }.toMap()
    }

    /**
     * Resolves the i18n key at the caret to its composite key and value leaves grouped by
     * source namespace. Returns null when there is no resolvable key under the caret.
     *
     * The namespace comes from the *resolved* translation sources (not the literal text), so a key
     * whose namespace is provided by a `useTranslation('ns')` / `useTranslation(['a','b'])` hook
     * resolves correctly. Read-only — performs no UI and no writes.
     */
    internal fun resolveKey(editor: Editor, psiFile: PsiFile): ResolvedKey? {
        val ref = findI18nReference(editor, psiFile) ?: return null

        val maxPath = ref.references.maxOfOrNull { it.reference.path.size } ?: return null
        if (maxPath == 0) return null
        val resolved = ref.references.filter { it.reference.path.size == maxPath && it.reference.unresolved.isEmpty() }
        if (resolved.isEmpty()) return null

        val defaultNamespace = Settings.getInstance(psiFile.project).config().defaultNamespaces().first()
        val compositeKey = resolved.first().reference.path
        val leavesByNamespace = resolved
            .mapNotNull { d ->
                val ns = namespaceOf(d.reference.localizationSource, defaultNamespace) ?: return@mapNotNull null
                val leaf = d.reference.element?.value() ?: return@mapNotNull null
                ns to leaf
            }
            .groupBy({ it.first }, { it.second })
        if (leavesByNamespace.isEmpty()) return null

        return ResolvedKey(ref.element, compositeKey, leavesByNamespace)
    }

    /**
     * Collects the code literals referencing the source entries (via the translation properties)
     * plus the literal under the caret. Read-only — must run inside a read action.
     */
    internal fun collectCodeUsages(sourceLeaves: List<PsiElement>, sourceCodeElement: PsiElement): List<PsiElement> {
        val sourceProps = sourceLeaves.mapNotNull { toPropertyElement(it) }
        return (sourceProps.flatMap { prop ->
            ReferencesSearch.search(prop).mapNotNull { it.element }
        } + sourceCodeElement).distinct()
    }

    // --- Helpers ---

    private fun findI18nReference(editor: Editor, psiFile: PsiFile): I18nReference? {
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return null
        val literal = element.parent ?: element
        return literal.references.filterIsInstance<I18nReference>().firstOrNull()
            ?: element.references.filterIsInstance<I18nReference>().firstOrNull()
    }

    /**
     * The namespace [source] holds, as the tool window names it: the one its module template or its
     * format states, else its file name, and the default namespace for a file named after its locale.
     */
    private fun namespaceOf(source: TranslationSource<PsiElement, *>, defaultNamespace: String): String? {
        // Every source the plugin resolves a key in is read through a Localization.
        @Suppress("UNCHECKED_CAST")
        val localizationSource = source as LocalizationSource
        return TranslationDataLoader.extractNamespace(localizationSource, defaultNamespace).ifBlank { null }
    }

    /** The key's path as the code writes it: the segments joined with the configured key separator. */
    private fun spellPath(compositeKey: List<Literal>, config: Config): String =
        compositeKey.joinToString(config.keySeparator) { it.text }

    /**
     * What the code writes between a namespace and its key, or null when it cannot name a
     * namespace at all: flat keys are never split, and without a namespace separator there is
     * nothing to write. A first-component namespace is joined with the key separator, as *Copy Key*
     * writes it.
     */
    private fun namespaceSeparatorOf(config: Config): String? = when {
        config.usesFlatKeys() -> null
        config.firstComponentNs -> config.keySeparator
        config.nsSeparator.isEmpty() -> null
        else -> config.nsSeparator
    }

    private fun fullKeyOf(namespace: String, compositeKey: List<Literal>, config: Config): FullKey = FullKey(
        source = namespaceSeparatorOf(config)?.let { namespace + it }.orEmpty() + spellPath(compositeKey, config),
        ns = Literal(namespace),
        compositeKey = compositeKey
    )

    /**
     * The rewrite of [literal] to [targetNs], or null when it does not write the moved key.
     *
     * The literal is read as the annotator reads it — the configured separators, a hook's namespace,
     * a module's key template — and compared on the key it names, not on its text: a textual
     * `endsWith` rebuilt with `.` and `:` missed every call of a project using other separators.
     * The path is kept as written; only the namespace in front of it changes, with the separator
     * the literal already uses when it names one. A call under a `keyPrefix` hook writes the tail
     * of the key only and cannot name a namespace: it is left alone, as is a flat key.
     */
    private fun rewriteOf(literal: PsiElement, compositeKey: List<Literal>, targetNs: String, config: Config): Rewrite? {
        val rawKey = Extensions.LANG.extensionList.firstNotNullOfOrNull { it.extractRawKey(literal) } ?: return null
        val fullKey = RawKeyParser(literal.project).parse(rawKey, literal) ?: return null
        if (fullKey.keyPrefix.isNotEmpty() || fullKey.compositeKey.map { it.text } != compositeKey.map { it.text }) return null

        val text = literal.text
        val quoted = text.isQuoted()
        val key = if (quoted) text.substring(1, text.length - 1) else text
        val namespacePart = writtenNamespaceOf(key, fullKey)
        val separator = fullKey.ns?.text?.let { namespacePart.removePrefix(it) }?.takeIf { it.isNotEmpty() }
            ?: namespaceSeparatorOf(config) ?: return null
        val rewritten = targetNs + separator + key.removePrefix(namespacePart)
        if (rewritten == key) return null

        val document = literal.containingFile?.viewProvider?.document ?: return null
        return Rewrite(document, literal.textRange, if (quoted) "${text.first()}$rewritten${text.last()}" else rewritten)
    }

    /**
     * The namespace and its separator at the start of [key] — `common:` in `common:user.name`,
     * `common.` with a first-component namespace, `courier::messages.` for a Laravel package —
     * or an empty string when the key writes none. Found between the namespace and the first key
     * segment of [fullKey]'s source, whatever separator the key's syntax uses.
     */
    private fun writtenNamespaceOf(key: String, fullKey: FullKey): String {
        val namespace = fullKey.ns?.text ?: return ""
        val source = fullKey.source
        if (!source.startsWith(namespace)) return ""
        val firstSegment = fullKey.compositeKey.firstOrNull()?.text ?: return ""
        val afterNamespace = source.substring(namespace.length)
        val separatorLength = afterNamespace.indexOf(firstSegment).takeIf { it > 0 } ?: return ""
        return source.substring(0, namespace.length + separatorLength).takeIf { key.startsWith(it) } ?: ""
    }

    /**
     * Inserts [leaf]'s value into [moveTarget], the target namespace's source of the same locale —
     * see [targetSourceFor] — creating the file when it does not exist yet (so no locale is silently dropped).
     */
    private fun insertIntoTarget(
        project: Project,
        leaf: PsiElement,
        moveTarget: MoveTarget,
        compositeKey: List<Literal>,
        targetNs: String,
        config: Config,
    ) {
        val value = readPsiValue(leaf) ?: return
        val target = sourceOf(project, moveTarget) ?: return

        val ref = resolveCompositeKey(compositeKey, target) ?: return
        val rootOrLeaf = ref.element?.value() ?: return
        val generator = target.localization.contentGenerator()
        if (ref.unresolved.isEmpty()) {
            updatePsiValue(rootOrLeaf, value, project)
        } else if (generator.isSuitable(rootOrLeaf)) {
            generator.generate(rootOrLeaf, fullKeyOf(targetNs, compositeKey, config), ref.unresolved, value)
        }
    }

    /**
     * The source the moved value goes to: [targetNs] in the locale of [sourceFile], which is
     *  1. the file the module's path template designates for that locale and namespace
     *     (`locales/{ns}/{lang}.json` puts `profile` in `locales/profile/fr.json`), created when missing;
     *  2. else a source of that namespace and locale the project already has — beside the source file
     *     first, for a format naming its files its own way (`errors_fr.properties`), else wherever it lives;
     *  3. else `<targetNs>.<ext>` beside the source file, created when missing.
     *
     * The sibling file used to be the only answer: in a folder-per-namespace module the key landed in
     * `locales/common/profile.json`, a file no template designates and nothing reads.
     *
     * Read-only: a file to create is only named here, [sourceOf] creates it in the write action.
     */
    private fun targetSourceFor(project: Project, sourceFile: PsiFile, targetNs: String, config: Config): MoveTarget? {
        val virtualFile = sourceFile.virtualFile ?: return null
        val extension = virtualFile.extension ?: return null
        val source = TranslationFileScope.sourceOf(sourceFile)
        val localization = source?.localization ?: localizationOf(extension) ?: return null

        if (source != null) {
            templateTargetOf(project, virtualFile, source, targetNs, config, localization)?.let { return it }
            val locale = TranslationDataLoader.extractLocale(source)
            val existing = project.service<LocalizationSourceService>().findSources(listOf(targetNs), sourceFile)
                .filter { TranslationDataLoader.extractLocale(it) == locale }
            (existing.firstOrNull { fileOf(it)?.parent == virtualFile.parent } ?: existing.firstOrNull())
                ?.let { return MoveTarget.Existing(it) }
        }

        val directory = virtualFile.parent ?: return null
        return MoveTarget.File(directory, "", "$targetNs.$extension", localization)
    }

    /** The source [target] names, its file — and folder — created when missing. Inside the write action. */
    private fun sourceOf(project: Project, target: MoveTarget): LocalizationSource? = when (target) {
        is MoveTarget.Existing -> target.source
        is MoveTarget.File -> {
            val directory = if (target.folder.isEmpty()) target.anchor
                else runCatching { VfsUtil.createDirectoryIfMissing(target.anchor, target.folder) }.getOrNull()
            directory?.let { PsiManager.getInstance(project).findDirectory(it) }
                ?.let { sourceIn(project, it, target.name, target.localization) }
        }
    }

    /**
     * The file the module template matching [virtualFile] designates for [targetNs] in the locale
     * of [source], or null when no template designates one — no module template matches the file,
     * or the template names no namespace (`{lang}.json`: one file per locale, nothing to move to).
     *
     * Anchored on the source file rather than on the project directory: the file matched the
     * template, so its path ends with the template resolved for its own locale and namespace, and
     * what precedes that is where the target resolves too — in a test fixture as in a project.
     */
    private fun templateTargetOf(
        project: Project,
        virtualFile: VirtualFile,
        source: LocalizationSource,
        targetNs: String,
        config: Config,
        localization: Localization<PsiElement>,
    ): MoveTarget.File? {
        val locale = source.locale ?: return null
        val ownNamespace = source.namespace ?: return null
        val path = ModuleSources.FilePath.of(virtualFile, project.basePath ?: "")
        val module = ModuleSources.active(config.modules).firstOrNull { ModuleSources.match(listOf(it), path) != null } ?: return null
        val template = ModuleTemplateResolver.combine(module)
        val extension = virtualFile.extension ?: return null

        fun resolved(namespace: String): String {
            val resolved = ModuleTemplateResolver.resolve(template, locale, namespace).trim('/')
            // A template naming no extension (`{lang}/{ns}`) designates a file of the source's format.
            return if (resolved.substringAfterLast('/').contains('.')) resolved else "$resolved.$extension"
        }

        val own = resolved(ownNamespace)
        if (!virtualFile.path.endsWith("/$own")) return null
        var anchor: VirtualFile = virtualFile
        repeat(own.count { it == '/' } + 1) { anchor = anchor.parent ?: return null }

        val target = resolved(targetNs)
        return MoveTarget.File(anchor, target.substringBeforeLast('/', ""), target.substringAfterLast('/'), localization)
    }

    /** The source read from [name] in [directory], the file created empty when missing. */
    private fun sourceIn(project: Project, directory: PsiDirectory, name: String, localization: Localization<PsiElement>): LocalizationSource? {
        val file: PsiFile = directory.findFile(name) ?: run {
            val created = PsiFileFactory.getInstance(project)
                .createFileFromText(name, localization.contentGenerator().getLanguage(), emptyRootContent(name.substringAfterLast('.')))
            directory.add(created) as PsiFile
        }
        val tree = localization.elementsTree(file) ?: return null
        return LocalizationSource(tree, name, directory.name, file.virtualFile?.path ?: name, localization)
    }

    private fun fileOf(source: LocalizationSource): VirtualFile? =
        (source.tree?.value() ?: source.host)?.containingFile?.virtualFile

    private fun localizationOf(extension: String): Localization<PsiElement>? =
        Extensions.LOCALIZATION.extensionList.find { loc -> loc.types().any { it.extensions().contains(extension) } }

    private fun toPropertyElement(element: PsiElement): PsiElement? = TranslationPsi.propertyOf(element)

    private fun readPsiValue(element: PsiElement): String? = TranslationPsi.readValue(element)

    private fun updatePsiValue(element: PsiElement, newValue: String, project: Project) =
        TranslationPsi.replaceValue(element, newValue, project)

    private fun hasCollision(project: Project, targetNs: String, compositeKey: List<Literal>, caller: PsiElement, config: Config): Boolean =
        ReadAction.compute<Boolean, RuntimeException> {
            DialogViewModel(project).loadTranslations(fullKeyOf(targetNs, compositeKey, config), caller).any { (_, v) -> v != null }
        }

    private fun emptyRootContent(ext: String): String =
        if (ext == "json" || ext == "json5") "{}" else ""

    private fun showNamespaceCombo(project: Project, label: String, namespaces: List<String>): String? {
        val combo = ComboBox(namespaces.toTypedArray())
        val panel = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(380, 60)
            add(JLabel(label), BorderLayout.NORTH)
            add(combo, BorderLayout.CENTER)
        }
        val dialog = object : DialogWrapper(project, true) {
            init {
                title = PluginBundle.message("action.move.title")
                init()
            }

            override fun createCenterPanel(): JComponent = panel
        }
        return if (dialog.showAndGet()) combo.selectedItem as? String else null
    }

    private fun String.isQuoted(): Boolean =
        length > 1 && (startsWith('"') && endsWith('"')
                || startsWith('\'') && endsWith('\'')
                || startsWith('`') && endsWith('`'))
}
