package com.ibrahimdans.i18n.extensions.technology.paraglide

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.extensions.technology.SimpleTechnology
import com.ibrahimdans.i18n.plugin.utils.pathToRoot
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
 * Paraglide JS (inlang): messages are compiled into functions, `m.hello()`, and no call names a key
 * as a string. The technology publishes no translation function, so a module whose preset is
 * `paraglide` stops claiming the generic `t('…')` calls: `m.hello` is no call a function name describes.
 *
 * Its message files are where each `project.inlang/settings.json` says ([InlangProjectSettings]):
 * any `pathPattern`, not only `messages/{locale}.json`, each file declared with its locale, and as
 * many inlang projects as a monorepo holds. A file the scan finds as well is kept once: the sources
 * share their display path.
 */
class ParaglideTechnology : SimpleTechnology() {
    override fun frameworkId(): String = "paraglide"

    override fun translationFunctionNames(): List<String> = emptyList()

    override fun findSourcesByConfiguration(project: Project): List<LocalizationSource> {
        if (DumbService.isDumb(project)) return emptyList()
        val basePath = project.basePath.orEmpty()
        return runReadAction {
            projects(project).flatMap { (folder, settings) ->
                settings.messageFiles().mapNotNull { (locale, path) -> sourceOf(project, folder, path, locale, basePath) }
            }
        }
    }

    private fun sourceOf(project: Project, folder: VirtualFile, path: String, locale: String, basePath: String): LocalizationSource? {
        val virtualFile = folder.findFileByRelativePath(path) ?: return null
        val file = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        val localization = Extensions.LOCALIZATION.extensionList
            .firstOrNull { localization -> localization.types().any { it.languageFileType == file.fileType } } ?: return null
        return LocalizationSource(
            localization.elementsTree(file),
            file.name,
            virtualFile.parent.name,
            pathToRoot(basePath, virtualFile.parent.path).trim('/') + '/' + file.name,
            localization,
            locale = locale,
        )
    }

    companion object {

        /**
         * Every inlang project of [project]: the folder holding its `project.inlang`, with its settings.
         * A `settings.json` outside a `project.inlang` folder, under `node_modules`, or unreadable is skipped.
         */
        internal fun projects(project: Project): List<Pair<VirtualFile, InlangProjectSettings>> =
            // Asked for every file an inspection looks at: kept until a file moves or a settings file changes.
            CachedValuesManager.getManager(project).getCachedValue(project) {
                val files = FilenameIndex.getVirtualFilesByName("settings.json", GlobalSearchScope.projectScope(project))
                    .filter { it.parent?.name == "project.inlang" && "/node_modules/" !in it.path }
                    .sortedBy { it.path }
                val found = files.mapNotNull { file ->
                    val folder = file.parent?.parent ?: return@mapNotNull null
                    val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return@mapNotNull null
                    InlangProjectSettings.parse(text)?.let { folder to it }
                }
                CachedValueProvider.Result.create(found, *files.toTypedArray(), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
            }

        /** The base locale of [project]'s first inlang project, the default for a preview locale; null without one. */
        fun baseLocale(project: Project): String? {
            if (DumbService.isDumb(project)) return null
            return runReadAction { projects(project).firstNotNullOfOrNull { it.second.baseLocale } }
        }
    }
}
