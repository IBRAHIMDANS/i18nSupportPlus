package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
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
     * The languages the plugin reads keys in, by id so that no optional plugin is needed to name them.
     * Their dialects follow: TypeScript, JSX and TSX are kinds of JavaScript.
     */
    private val CODE_LANGUAGES = setOf("JavaScript", "Vue", "PHP", "Blade", "Angular2", "Angular2Html", "SvelteHTML", "Astro")

    /** True when [file] is source code of a language the plugin reads keys in. */
    fun isCode(file: PsiFile): Boolean =
        file.viewProvider.languages.any { language -> CODE_LANGUAGES.any { language.isKindOf(it) } }

    /** True when [file] is code the plugin reads keys in, or a translation file. */
    fun isCodeOrTranslation(file: PsiFile): Boolean = isCode(file) || TranslationFileScope.sourceOf(file) != null

    /** True when the project of [file] has at least one translation file; the scan is cached by the service. */
    fun hasSources(file: PsiFile): Boolean =
        file.project.service<LocalizationSourceService>().findAllSources(file.project).isNotEmpty()
}
