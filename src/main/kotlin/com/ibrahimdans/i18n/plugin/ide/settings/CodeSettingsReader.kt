package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.CodeReader
import com.ibrahimdans.i18n.Extensions
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

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
     * The files of [project] that initialise this technology, found by what they hold wherever they
     * are: what the wizard reads when none of [I18nextCliConfig.WIZARD_FILE_NAMES] states anything.
     * Empty while the indexes are built. Searches the indexes: asked off the EDT, holding no read action.
     */
    fun wizardInitFiles(project: Project): List<VirtualFile> = emptyList()

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

        /** Every registered reader's [wizardInitFiles], in registration order. Off the EDT. */
        fun wizardInitFiles(project: Project): List<VirtualFile> = all().flatMap { it.wizardInitFiles(project) }.distinct()

        /**
         * What the wizard reads in [files], the [wizardInitFiles] of the project under [base], when none
         * of [I18nextCliConfig.WIZARD_FILE_NAMES] stated anything: the one file left once those are set
         * aside, read like an initialisation file ([wizardCliConfig]). Null when there are none — or
         * several: a monorepo initialises i18next once per application, and one of them taken for the
         * whole project would override the per-application modules the scan proposes. Opens its own
         * read action.
         */
        fun wizardCliConfigOf(
            project: Project,
            base: VirtualFile,
            files: List<VirtualFile>,
            isDirectory: (String) -> Boolean,
        ): I18nextCliConfig? {
            val found = files
                .mapNotNull { file -> VfsUtilCore.getRelativePath(file, base)?.let { it to file } }
                .filter { (relative, _) -> relative !in I18nextCliConfig.WIZARD_FILE_NAMES }
                .distinctBy { it.first }
            val (relative, file) = found.singleOrNull() ?: return null
            return runReadAction {
                if (!file.isValid) return@runReadAction null
                val psi = PsiManager.getInstance(project).findFile(file) ?: return@runReadAction null
                all().firstNotNullOfOrNull { it.wizardCliConfig(psi, relative, isDirectory) }
            }
        }
    }
}
