package com.ibrahimdans.i18n.plugin.review

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Where the reviews of a module live: `<module root>/.i18n-reviews/<locale>.json`, or under the
 * project's directory for a project without modules. One file per locale, so that the reviewers of
 * two languages never edit the same file; one folder per module, so that two applications of a
 * monorepo sharing a key do not share its review.
 *
 * The folder is the switch: present, reviews are on for the module — it is versioned, so the whole
 * team gets them; absent, nothing is created unless a person asks. Never under `.idea/`, often
 * ignored, nor next to the translations, where it would be read as one (it is excluded from the
 * discovery of sources, see `LocalizationSourceService`).
 */
object ReviewLocation {

    const val DIRECTORY = ".i18n-reviews"

    /** The file holding the reviews of [locale] inside the reviews folder. */
    fun fileName(locale: String): String = "$locale.json"

    /**
     * The directory [module]'s reviews folder hangs under: its root directory, relative to the project
     * as `ModuleSources` reads it, or the project's directory when there is no module or it names no
     * root. Null when that directory does not exist.
     *
     * With a lone configured module ([ModuleSources.lone]), that module and no module are one scope:
     * the tool window, *Fill* and the agents ask without it, the key dialog, the inspection and the
     * key moves with it. Both read the folder already there — the module's, else the project's — so
     * that reviews written by one are read by the other, wherever they were written; neither moves.
     */
    fun rootOf(project: Project, module: ModuleConfig?): VirtualFile? {
        val own = ownRootOf(project, module)
        val lone = ModuleSources.lone(Settings.getInstance(project).modules) ?: return own
        if (module != null && module != lone) return own
        return shared(listOf(ownRootOf(project, lone), project.guessProjectDir()), own) { directoryOf(it) != null }
    }

    /**
     * The first of [candidates] where reviews are on ([enabled]), else [own]: where a scope with no
     * folder yet would create it. Pure, tested on its own.
     */
    internal fun <T : Any> shared(candidates: List<T?>, own: T?, enabled: (T) -> Boolean): T? =
        candidates.filterNotNull().firstOrNull(enabled) ?: own

    private fun ownRootOf(project: Project, module: ModuleConfig?): VirtualFile? {
        if (module == null || module.rootDirectory.isBlank()) return project.guessProjectDir()
        val path = rootPathOf(module, project.basePath) ?: return null
        return LocalFileSystem.getInstance().findFileByPath(path)?.takeIf { it.isDirectory }
    }

    /**
     * The path of [module]'s root directory under [basePath], or null when there is none to resolve.
     * Pure, tested on its own: a test fixture's project-relative paths are not where its files are.
     */
    internal fun rootPathOf(module: ModuleConfig, basePath: String?): String? {
        val root = module.rootDirectory.trim().trim('/')
        if (root.isEmpty() || basePath.isNullOrBlank()) return null
        return basePath.trimEnd('/') + "/" + root
    }

    /** [root]'s reviews folder, or null when reviews are off there. */
    fun directoryOf(root: VirtualFile): VirtualFile? = root.findChild(DIRECTORY)?.takeIf { it.isDirectory }

    /** True when [path] lies inside a reviews folder, or is one. */
    fun isReviewPath(path: String): Boolean = path.replace('\\', '/').split('/').contains(DIRECTORY)
}
