package com.ibrahimdans.i18n.plugin.ide.annotator

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.extensions.technology.paraglide.ParaglideTechnology
import com.ibrahimdans.i18n.plugin.ide.annotator.KeyDiagnosis.NamespaceOrigin
import com.ibrahimdans.i18n.plugin.ide.quickfix.CreateKeyDialogQuickFix
import com.ibrahimdans.i18n.plugin.ide.quickfix.CreateMissingKeysQuickFix
import com.ibrahimdans.i18n.plugin.ide.quickfix.CreateTranslationFileQuickFix
import com.ibrahimdans.i18n.plugin.ide.settings.Configurable
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.parser.KeySyntax
import com.ibrahimdans.i18n.plugin.parser.KeyTemplate
import com.ibrahimdans.i18n.plugin.tree.PropertyReference
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.RangesCalculator
import com.ibrahimdans.i18n.plugin.utils.hostVirtualFile
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.lang.annotation.AnnotationBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.xml.util.XmlStringUtil

/**
 * Annotation helper methods
 */
class AnnotationHelper(private val holder: AnnotationHolder, private val rangesCalculator: RangesCalculator) {

    private val errorSeverity = HighlightSeverity.ERROR
    private val infoSeverity = HighlightSeverity.INFORMATION

    /**
     * Annotates resolved translation key
     */
    fun annotateResolved(fullKey: FullKey) {
        holder
            .newAnnotation(infoSeverity, "")
            .range(rangesCalculator.compositeKeyFullBounds(fullKey))
            .textAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT)
            .create()
    }

    /**
     * Annotates reference to object, not a leaf key in translation
     */
    fun annotateReferenceToObject(fullKey: FullKey) {
        holder
            .newAnnotation(errorSeverity, PluginBundle.getMessage("annotator.object.reference"))
            .range(rangesCalculator.compositeKeyFullBounds(fullKey))
            .create()
    }

    /**
     * Annotates unresolved namespace
     */
    fun unresolvedNs(fullKey: FullKey, ns: Literal) {
        val message = PluginBundle.getMessage("annotator.unresolved.ns")
        val builder = holder
            .newAnnotation(errorSeverity, message)
            .range(rangesCalculator.unresolvedNs(fullKey))
            .explained(message, fullKey, null)
        Extensions.LOCALIZATION.extensionList.forEach {
            builder.withFix(CreateTranslationFileQuickFix(fullKey, it.contentGenerator(), ns.text))
        }
        builder.create()
    }

    /**
     * Annotates unresolved default namespace
     */
    fun unresolvedDefaultNs(fullKey: FullKey) {
        val message = PluginBundle.getMessage("annotator.missing.default.ns")
        holder
            .newAnnotation(errorSeverity, message)
            .range(rangesCalculator.compositeKeyFullBounds(fullKey))
            .explained(message, fullKey, null)
            .create()
    }

    /**
     * Annotates unresolved composite key
     */
    fun unresolvedKey(fullKey: FullKey, mostResolvedReference: PropertyReference) {
        val message = PluginBundle.getMessage("annotator.unresolved.key")
        val builder = holder
            .newAnnotation(errorSeverity, message)
            .range(rangesCalculator.unresolvedKey(fullKey, mostResolvedReference.path))
            .explained(message, fullKey, mostResolvedReference)
        // A key with a runtime segment names no property to create: the fix would write the
        // `${…}` text as a property name. One fix, one dialog: it shows every locale, so a
        // second "in all files" entry has nothing left to add.
        if (!fullKey.isDynamic) {
            builder.withFix(CreateKeyDialogQuickFix(fullKey, PluginBundle.getMessage("quickfix.create.key")))
        }
        builder.create()
    }

    /**
     * Annotates partially translated key and creates quick fix for it.
     */
    fun annotatePartiallyTranslated(fullKey: FullKey, references: List<PropertyReference>) {
        references.minByOrNull { it.path.size }?.let {
            val builder = holder
                .newAnnotation(errorSeverity, PluginBundle.getMessage("annotator.partially.translated"))
                .range(rangesCalculator.unresolvedKey(fullKey, it.path))
            builder.withFix(CreateMissingKeysQuickFix(fullKey, references, PluginBundle.getMessage("quickfix.create.missing.keys")))
            builder.create()
        }
    }

    /**
     * Under the annotation's [message], the lines of [KeyDiagnosis] saying how [fullKey] was read and
     * where it was looked up — and a fix opening the settings those lines point at.
     *
     * The settings are reached through a fix rather than a link in the tooltip: a link needs a
     * `codeInsight.linkHandler` registered by the plugin, while the fix shows in the tooltip's
     * actions and under Alt+Enter with nothing to declare.
     */
    private fun AnnotationBuilder.explained(message: String, fullKey: FullKey, reference: PropertyReference?): AnnotationBuilder {
        val lines = diagnose(fullKey, holder.currentAnnotationSession.file, reference).explain()
        val html = (listOf(message) + lines).joinToString("<br/>") { XmlStringUtil.escapeString(it) }
        return tooltip(XmlStringUtil.wrapInHtml(html)).withFix(OpenSettingsFix())
    }

    /**
     * The facts of [KeyDiagnosis], asked of the lookup the annotator itself runs — the same
     * [LocalizationSourceService] calls, for the file the key is written in — so the tooltip
     * explains that lookup rather than a copy of it.
     */
    private fun diagnose(fullKey: FullKey, file: PsiFile, reference: PropertyReference?): KeyDiagnosis {
        val project = file.project
        val config = Settings.getInstance(project).config()
        val basePath = project.basePath ?: ""
        val module = file.hostVirtualFile()?.let { ModuleSources.owner(config.modules, ModuleSources.FilePath.of(it, basePath)) }
        // Flat keys are never split, whatever the template says (RawKeyParser).
        val syntax = module?.takeUnless { config.usesFlatKeys() }?.let { KeyTemplate.parse(it.keyTemplate) }
        val namespaces = fullKey.lookupNamespaces().ifEmpty { config.defaultNamespaces() }
        val sourceService = project.service<LocalizationSourceService>()
        val searched = sourceService.findReadSources(fullKey, file)
        val origin = when {
            fullKey.ns != null -> if (syntax is KeySyntax.Namespaced) NamespaceOrigin.KEY_TEMPLATE else NamespaceOrigin.WRITTEN
            !fullKey.namespaces.isNullOrEmpty() -> NamespaceOrigin.CALL
            module?.preset == PARAGLIDE || readsParaglideMessages(searched, project) -> NamespaceOrigin.NONE
            syntax == KeySyntax.NoNamespace -> NamespaceOrigin.KEY_TEMPLATE_DEFAULT
            else -> NamespaceOrigin.DEFAULT
        }
        val outside = if (module == null || searched.isNotEmpty()) emptyList() else {
            sourceService.findNamespaceFiles(namespaces, project).filterNot { source ->
                source.virtualFile()?.let { ModuleSources.contains(module, ModuleSources.FilePath.of(it, basePath)) } ?: true
            }
        }
        val missing = reference?.unresolved?.firstOrNull()
        return KeyDiagnosis(
            namespaces = namespaces,
            origin = origin,
            keyTemplate = module?.keyTemplate?.trim()?.takeIf { syntax != null },
            module = module?.let { it.name.ifBlank { it.rootDirectory } },
            searched = searched.map { it.displayPath }.distinct(),
            outsideModule = outside.map { it.displayPath }.distinct(),
            found = reference?.takeIf { missing != null }?.path?.joinToString(config.keySeparator) { it.text }.orEmpty(),
            missing = missing?.text,
        )
    }

    /** Every file [searched] is a message file of an inlang project: one per locale, no namespace. */
    private fun readsParaglideMessages(searched: List<LocalizationSource>, project: Project): Boolean {
        if (searched.isEmpty() || ParaglideTechnology.projects(project).isEmpty()) return false
        val messages = ParaglideTechnology().findSourcesByConfiguration(project).mapTo(HashSet()) { it.displayPath }
        return searched.all { it.displayPath in messages }
    }

    private fun LocalizationSource.virtualFile() = (tree?.value() ?: host)?.containingFile?.virtualFile

    /** Opens the plugin's settings: modules, key templates, separators, translations root. */
    private companion object {
        const val PARAGLIDE = "paraglide"
    }

    private class OpenSettingsFix : IntentionAction, LowPriorityAction {
        override fun getText(): String = PluginBundle.getMessage("annotator.diagnosis.open.settings")
        override fun getFamilyName(): String = text
        override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true
        override fun startInWriteAction(): Boolean = false
        override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, Configurable::class.java)
        }
    }
}
