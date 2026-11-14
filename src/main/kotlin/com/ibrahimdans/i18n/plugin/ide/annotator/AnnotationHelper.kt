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
import com.ibrahimdans.i18n.plugin.utils.IdeWorkspace
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.of
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.RangesCalculator
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import com.ibrahimdans.i18n.plugin.utils.hostVirtualFile
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.xml.util.XmlStringUtil

/**
 * What [KeyCheck] says of a key, as the messages, ranges and fixes of a [KeyReport]: the
 * annotator's colouring or the inspection's problems. [file] is the file the key is written in.
 */
class AnnotationHelper(
    private val report: KeyReport,
    private val rangesCalculator: RangesCalculator,
    private val file: PsiFile,
) {

    /**
     * Annotates resolved translation key
     */
    fun annotateResolved(fullKey: FullKey) {
        report.resolved(rangesCalculator.compositeKeyFullBounds(fullKey).toTextRange())
    }

    /**
     * Annotates reference to object, not a leaf key in translation
     */
    fun annotateReferenceToObject(fullKey: FullKey) {
        report.problem(
            rangesCalculator.compositeKeyFullBounds(fullKey).toTextRange(),
            PluginBundle.getMessage("annotator.object.reference"),
            weak = false, tooltip = null, fixes = emptyList(),
        )
    }

    /**
     * Annotates unresolved namespace. [similarNamespaces] offer the namespaces a typo away, before
     * *Create translation file*.
     */
    fun unresolvedNs(fullKey: FullKey, ns: Literal, similarNamespaces: List<IntentionAction> = emptyList()) {
        val message = PluginBundle.getMessage("annotator.unresolved.ns")
        val fixes = listOf<IntentionAction>(OpenSettingsFix()) + similarNamespaces + Extensions.LOCALIZATION.extensionList.map {
            CreateTranslationFileQuickFix(fullKey, it.contentGenerator(), ns.text)
        }
        report.problem(
            rangesCalculator.unresolvedNs(fullKey).toTextRange(), message,
            weak = false, tooltip = { explain(message, fullKey, null) }, fixes = fixes,
        )
    }

    /**
     * Annotates unresolved default namespace
     */
    fun unresolvedDefaultNs(fullKey: FullKey) {
        val message = PluginBundle.getMessage("annotator.missing.default.ns")
        report.problem(
            rangesCalculator.compositeKeyFullBounds(fullKey).toTextRange(), message,
            weak = false, tooltip = { explain(message, fullKey, null) }, fixes = listOf(OpenSettingsFix()),
        )
    }

    /**
     * Annotates unresolved composite key. With the [defaultValue] the call shows meanwhile
     * (i18next's `t('key', 'Text')`), a weak warning: the screen reads that text, the key is still
     * to create — and *Create key* proposes the text for the reference locale. [similarKeys] offer
     * the keys a typo away, before *Create key*.
     */
    fun unresolvedKey(
        fullKey: FullKey,
        mostResolvedReference: PropertyReference<PsiElement>,
        defaultValue: String? = null,
        similarKeys: List<IntentionAction> = emptyList(),
    ) {
        val message = PluginBundle.getMessage(if (defaultValue == null) "annotator.unresolved.key" else "annotator.unresolved.key.default.value")
        // A key with a runtime segment names no property to create: the fix would write the
        // `${…}` text as a property name. One fix, one dialog: it shows every locale, so a
        // second "in all files" entry has nothing left to add.
        val createKey = CreateKeyDialogQuickFix(fullKey, PluginBundle.getMessage("quickfix.create.key"), defaultValue)
            .takeUnless { fullKey.isDynamic }
        report.problem(
            rangesCalculator.unresolvedKey(fullKey, mostResolvedReference.path).toTextRange(), message,
            weak = defaultValue != null,
            tooltip = { explain(message, fullKey, mostResolvedReference) },
            fixes = listOf<IntentionAction>(OpenSettingsFix()) + similarKeys + listOfNotNull(createKey),
        )
    }

    /**
     * Annotates partially translated key and creates quick fix for it.
     */
    fun annotatePartiallyTranslated(fullKey: FullKey, references: List<PropertyReference<PsiElement>>) {
        references.minByOrNull { it.path.size }?.let {
            report.problem(
                rangesCalculator.unresolvedKey(fullKey, it.path).toTextRange(),
                PluginBundle.getMessage("annotator.partially.translated"),
                weak = false, tooltip = null,
                fixes = listOf(CreateMissingKeysQuickFix(references, PluginBundle.getMessage("quickfix.create.missing.keys"))),
            )
        }
    }

    /**
     * The tooltip of a problem: its [message], then the lines of [KeyDiagnosis] saying how
     * [fullKey] was read and where it was looked up. The problem also carries a fix opening the
     * settings those lines point at.
     *
     * The settings are reached through a fix rather than a link in the tooltip: a link needs a
     * `codeInsight.linkHandler` registered by the plugin, while the fix shows in the tooltip's
     * actions and under Alt+Enter with nothing to declare.
     */
    private fun explain(message: String, fullKey: FullKey, reference: PropertyReference<PsiElement>?): String {
        val lines = diagnose(fullKey, file, reference).explain()
        val html = (listOf(message) + lines).joinToString("<br/>") { XmlStringUtil.escapeString(it) }
        return XmlStringUtil.wrapInHtml(html)
    }

    /**
     * The facts of [KeyDiagnosis], asked of the lookup the annotator itself runs — the same
     * [LocalizationSourceService] calls, for the file the key is written in — so the tooltip
     * explains that lookup rather than a copy of it.
     */
    private fun diagnose(fullKey: FullKey, file: PsiFile, reference: PropertyReference<PsiElement>?): KeyDiagnosis {
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
        val messages = ParaglideTechnology().findSourcesByConfiguration(IdeWorkspace(project)).mapTo(HashSet()) { it.displayPath }
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

/** [TextSpan] as the platform's range, for an annotation. */
internal fun TextSpan.toTextRange(): TextRange = TextRange(start, end)

/** The platform's range as a [TextSpan], for [com.ibrahimdans.i18n.plugin.utils.RangesCalculator]. */
internal fun TextRange.toTextSpan(): TextSpan = TextSpan(startOffset, endOffset)
