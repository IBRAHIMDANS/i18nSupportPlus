package com.ibrahimdans.i18n.extensions.technology.angulari18n

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.Technology
import com.ibrahimdans.i18n.Workspace
import com.ibrahimdans.i18n.extensions.localization.xliff.XliffFileType
import com.ibrahimdans.i18n.extensions.localization.xliff.XliffLocalization
import com.ibrahimdans.i18n.extensions.localization.xliff.XliffRole
import com.ibrahimdans.i18n.extensions.localization.xliff.XliffTree
import com.ibrahimdans.i18n.plugin.utils.pathToRoot
import com.ibrahimdans.i18n.plugin.utils.project
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * Angular's native i18n: the messages are marked in the templates (`i18n="@@home.title"`) and the code
 * (`` $localize`:@@home.title:Welcome` ``), extracted by `ng extract-i18n` into `messages.xlf`, and
 * translated into one XLIFF file per locale that `angular.json` lists under `i18n.locales`.
 *
 * The sources are those files, each with the locale `angular.json` gives it, the extracted file holding
 * the `sourceLocale` — the reference. A project of the workspace without `i18n`, or whose files are no
 * XLIFF (`json`, `xmb`), declares none. The files hold no namespace: every key of the code is looked up
 * in all of them, as Angular has a single catalogue per locale.
 *
 * The technology publishes no translation function: the keys are read by their own syntax, which no
 * other framework shares.
 */
class AngularI18nTechnology : Technology {

    override fun translationFunctionNames(): List<String> = emptyList()

    override fun initialize(workspace: Workspace) {}

    override fun cfgNamespaces(): List<String> = emptyList()

    override fun findSourcesByConfiguration(workspace: Workspace): List<LocalizationSource> {
        val project = workspace.project
        if (DumbService.isDumb(project)) return emptyList()
        val localization = Extensions.LOCALIZATION.extensionList.filterIsInstance<XliffLocalization>().firstOrNull() ?: return emptyList()
        val basePath = project.basePath.orEmpty()
        return runReadAction {
            workspaces(project).flatMap { (folder, projects) ->
                projects.flatMap { angular -> sourcesOf(project, folder, angular, localization, basePath) }
            }.distinctBy { it.displayPath }
        }
    }

    private fun sourcesOf(
        project: Project,
        folder: VirtualFile,
        angular: AngularI18nProject,
        localization: XliffLocalization,
        basePath: String,
    ): List<LocalizationSource> {
        val reference = angular.referenceFiles.firstNotNullOfOrNull { xliffAt(folder, it) }
            ?.let { sourceOf(project, it, angular.sourceLocale, XliffRole.SOURCE, localization, basePath) }
        val translations = angular.translations.mapNotNull { (locale, path) ->
            xliffAt(folder, path)?.let { sourceOf(project, it, locale, XliffRole.TARGET, localization, basePath) }
        }
        return listOfNotNull(reference) + translations
    }

    private fun xliffAt(folder: VirtualFile, path: String): VirtualFile? =
        folder.findFileByRelativePath(path)?.takeIf { !it.isDirectory && it.extension?.lowercase() in XliffFileType.EXTENSIONS }

    private fun sourceOf(
        project: Project,
        virtualFile: VirtualFile,
        locale: String,
        role: XliffRole,
        localization: XliffLocalization,
        basePath: String,
    ): LocalizationSource? {
        val file = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        return LocalizationSource(
            XliffTree(file, role),
            file.name,
            virtualFile.parent.name,
            pathToRoot(basePath, virtualFile.parent.path).trim('/') + '/' + file.name,
            localization,
            locale = locale,
        )
    }

    companion object {

        /**
         * Every Angular workspace of [project] with i18n: the folder holding its `angular.json`, with its
         * projects that configure i18n. One under `node_modules`, or unreadable, is skipped.
         */
        internal fun workspaces(project: Project): List<Pair<VirtualFile, List<AngularI18nProject>>> =
            // Asked on every lookup of sources: kept until a file moves or an `angular.json` changes.
            CachedValuesManager.getManager(project).getCachedValue(project) {
                val files = FilenameIndex.getVirtualFilesByName("angular.json", GlobalSearchScope.projectScope(project))
                    .filter { "/node_modules/" !in it.path }
                    .sortedBy { it.path }
                val found = files.mapNotNull { file ->
                    val folder = file.parent ?: return@mapNotNull null
                    val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return@mapNotNull null
                    AngularI18nConfig.parse(text).takeIf { it.isNotEmpty() }?.let { folder to it }
                }
                CachedValueProvider.Result.create(found, *files.toTypedArray(), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
            }
    }
}
