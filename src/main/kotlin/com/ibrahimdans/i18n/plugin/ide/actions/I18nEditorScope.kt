package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.usages.CodeUsageIndexService
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.components.service
import com.intellij.psi.PsiFile

/**
 * Where the editor actions that act on the whole view (*Show Translations Inline*, *Switch Preview
 * Locale*) are offered: they used to show in the context menu of every editor, a Java or Markdown
 * file of a project holding no translation included.
 */
internal object I18nEditorScope {

    /**
     * True when [file] is source code the plugin reads keys in: its file type is one a registered `Lang`
     * declares (`Lang.indexes`), the very files the usage index reads — so a template of a new `Lang`, Twig
     * included, gets the actions without a list to keep here. By the file type alone, not the languages of
     * its view: a Markdown file holds HTML too. Plain HTML counts, since JavaScript is read in it (Angular,
     * Svelte templates); the actions still show only in a project that has translations ([hasSources]).
     */
    fun isCode(file: PsiFile): Boolean = CodeUsageIndexService.isCodeType(file.fileType)

    /** True when [file] is code the plugin reads keys in, or a translation file. */
    fun isCodeOrTranslation(file: PsiFile): Boolean = isCode(file) || TranslationFileScope.sourceOf(file) != null

    /** True when the project of [file] has at least one translation file; the scan is cached by the service. */
    fun hasSources(file: PsiFile): Boolean =
        file.project.service<LocalizationSourceService>().findAllSources(file.project).isNotEmpty()
}
