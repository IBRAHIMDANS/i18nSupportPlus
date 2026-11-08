package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.CodeReader
import com.ibrahimdans.i18n.Extensions
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * A [CodeReader] of what the settings leave blank and what the setup wizard proposes, read in the
 * project's code. The trunk names no language: it asks the registered readers, through
 * [Extensions.CODE_READER], and takes the first answer. That is what keeps a language's PSI behind
 * its plugin — the settings never see JavaScript, and read nothing without it.
 *
 * Every method answers null by default: a reader implements the ones it has a file for.
 */
interface CodeSettingsReader : CodeReader {

    /**
     * The settings [CodeDefaults] fills, read from the code under [config]: null when the code says
     * nothing, or while the indexes are built. Asked from any thread; opens its own read action.
     */
    fun codeDefaults(project: Project, config: Config): CodeDefaults? = null

    /**
     * What [file], one of [I18nextCliConfig.WIZARD_FILE_NAMES] named [relativePath], states for the
     * wizard; null when this technology does not read that file. [isDirectory] tells whether a
     * project-relative folder exists. Called in a read action.
     */
    fun wizardCliConfig(file: PsiFile, relativePath: String, isDirectory: (String) -> Boolean): I18nextCliConfig? = null

    /**
     * What [file], a Nuxt or vue-i18n file of [VueI18nConfig.NUXT_FILE_NAMES] or
     * [VueI18nConfig.VUE_I18N_FILE_NAMES] named [relativePath], states for the wizard; null when this
     * technology does not read that file. Called in a read action.
     */
    fun wizardVueConfig(file: PsiFile, relativePath: String, isDirectory: (String) -> Boolean): VueI18nConfig? = null

    companion object {

        /** The registered readers, in registration order: none without a language plugin. */
        fun all(): List<CodeSettingsReader> = Extensions.CODE_READER.extensionList.filterIsInstance<CodeSettingsReader>()

        /** The first [codeDefaults] a registered reader reads under [config], null when none does. */
        fun codeDefaults(project: Project, config: Config): CodeDefaults? =
            all().firstNotNullOfOrNull { it.codeDefaults(project, config) }
    }
}
