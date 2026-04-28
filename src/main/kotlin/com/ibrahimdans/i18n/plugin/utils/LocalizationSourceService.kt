package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Localization
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.util.PsiTreeUtil

@Service
class LocalizationSourceService {

    companion object {
        private val DEFAULT_EXCLUDED_DIRS = setOf(
            "node_modules", "build", "dist", ".next", "out",
            "storybook-static", ".nuxt", ".output", "coverage", ".cache", "vendor"
        )
    }

    /**
     * Returns true if the file resides in a directory that should be excluded from translation scanning.
     * Uses IntelliJ's ProjectFileIndex first (respects .gitignore and IDE exclusions),
     * then falls back to a hardcoded list + user-configured excluded directories.
     */
    private fun isExcludedPath(file: VirtualFile, project: Project): Boolean {
        if (ProjectFileIndex.getInstance(project).isExcluded(file)) return true
        val customExclusions = Settings.getInstance(project).config().excludedDirectorySet()
        val allExclusions = DEFAULT_EXCLUDED_DIRS + customExclusions
        val segments = file.path.split('/')
        return segments.any { it in allExclusions }
    }

    fun findSources(fileNames: List<String>, project: Project): List<LocalizationSource> {
        return (findVirtualFilesByName(project,
            fileNames.whenMatches { it.isNotEmpty() } ?: Settings.getInstance(project).config().defaultNamespaces()
        ) + findSourcesByConfiguration(project))
            .distinctBy { it.displayPath }
    }

    fun findNamespaceFiles(fileNames: List<String>, project: Project): List<LocalizationSource> {
        if (fileNames.isEmpty()) return emptyList()
        return findVirtualFilesByName(project, fileNames).distinctBy { it.displayPath }
    }

    /**
     * Finds all localization sources in the project regardless of filename.
     * Used by the table/tree views to display all translations, including projects
     * with multiple namespaces (common.json, auth.json, etc.) or locale-named files (en.json).
     *
     * Strategy:
     *  - If [Config.translationsRoot] is set (e.g. "src/locales"), only files under that path are included.
     *  - Otherwise, falls back to a locale-heuristic: parent dir or stem must look like a locale code.
     */
    fun findAllSources(project: Project): List<LocalizationSource> {
        val config = Settings.getInstance(project).config()
        val basePath = project.basePath ?: ""
        return Extensions.LOCALIZATION.extensionList.flatMap { findAllSourcesByFileType(project, it, config.translationsRoot, basePath) } +
                findSourcesByConfiguration(project)
    }

    private fun findAllSourcesByFileType(
        project: Project,
        localization: Localization<PsiElement>,
        translationsRoot: String,
        basePath: String
    ): List<LocalizationSource> {
        return runReadAction {
            val searchScope = Settings.getInstance(project).config().searchScope(project)
            localization.types().flatMap { localizationType ->
                FileTypeIndex
                    .getFiles(localizationType.languageFileType, searchScope)
                    .filter { file -> !isExcludedPath(file, project) && isIncluded(file, translationsRoot, basePath) }
                    .mapNotNull { virtualFile ->
                        PsiManager.getInstance(project).findFile(virtualFile)?.let { file ->
                            val dir = file.containingDirectory ?: return@let null
                            LocalizationSource(
                                localization.elementsTree(file),
                                file.name,
                                dir.name,
                                pathToRoot(
                                    file.project.basePath ?: "",
                                    dir.virtualFile.path
                                ).trim('/') + '/' + file.name,
                                localization
                            )
                        }
                    }
            }
        }
    }

    /**
     * Decides whether to include a file in the "all sources" scan.
     * - Configured root: include only files whose path starts with basePath/translationsRoot.
     * - No root configured: heuristic — parent dir or stem must look like a locale code.
     */
    private fun isIncluded(file: VirtualFile, translationsRoot: String, basePath: String): Boolean {
        return if (translationsRoot.isNotBlank()) {
            val rootPath = "$basePath/$translationsRoot".trimEnd('/')
            file.path.startsWith(rootPath)
        } else {
            val parent = file.parent?.name ?: return false
            val stem = file.nameWithoutExtension
            looksLikeLocale(parent) || looksLikeLocale(stem)
        }
    }

    private fun looksLikeLocale(name: String): Boolean =
        name.matches(Regex("[a-zA-Z]{2,3}([_-][a-zA-Z]{2,4})?"))

    private fun findSourcesByConfiguration(project: Project): List<LocalizationSource> {
        return Extensions.TECHNOLOGY.extensionList.flatMap {it.findSourcesByConfiguration(project)}
    }

    //    Finds virtual files by names and type
    private fun findVirtualFilesByName(project: Project, fileNames: List<String>): List<LocalizationSource> {
        return Extensions.LOCALIZATION.extensionList.flatMap {findSourcesByFileType(project, fileNames, it)}
    }

    private fun findSourcesByFileType(project: Project, fileNames: List<String>, localization: Localization<PsiElement>): List<LocalizationSource> {
        return runReadAction {
            val searchScope = Settings.getInstance(project).config().searchScope(project)
            localization.types().flatMap { localizationType ->
                FileTypeIndex
                    .getFiles(localizationType.languageFileType, searchScope)
                    .filter { file -> !isExcludedPath(file, project) && localization.matches(localizationType, file, fileNames) }
                    .mapNotNull { virtualFile ->
                        PsiManager.getInstance(project).findFile(virtualFile)?.let { file ->
                            val dir = file.containingDirectory ?: return@let null
                            LocalizationSource(
                                localization.elementsTree(file),
                                file.name,
                                dir.name,
                                pathToRoot(
                                    file.project.basePath ?: "",
                                    dir.virtualFile.path
                                ).trim('/') + '/' + file.name,
                                localization
                            )
                        }
                    }
            }
        }
    }
}
