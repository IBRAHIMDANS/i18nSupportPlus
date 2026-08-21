package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Localization
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import java.lang.ref.SoftReference

@Service
class LocalizationSourceService {

    companion object {
        private val ALL_SOURCES_CACHE =
            Key.create<SoftReference<CachedSources>>("i18n.localization.allSources")

        private val DEFAULT_EXCLUDED_DIRS = setOf(
            "node_modules", "build", "dist", ".next", "out",
            "storybook-static", ".nuxt", ".output", "coverage", ".cache", "vendor"
        )

        // ISO 639-1 (2 letters), their ISO 639-2 equivalents ("fra", "deu"), and the
        // 3-letter languages known to the JDK's CLDR data ("fil", "haw"). The full
        // ISO 639-3 registry is deliberately not used: its ~7900 codes collide with
        // common directory names ("api" is Apiaká).
        private val ISO_LANGUAGES: Set<String> = buildSet {
            val twoLetter = java.util.Locale.getISOLanguages()
            addAll(twoLetter)
            twoLetter.forEach { code ->
                runCatching { add(java.util.Locale.of(code).isO3Language) }
            }
            java.util.Locale.getAvailableLocales().forEach { locale ->
                if (locale.language.isNotEmpty()) add(locale.language)
            }
        }

        private val ISO_COUNTRIES: Set<String> = java.util.Locale.getISOCountries().toSet()

        /**
         * True when [name] is a plausible locale code: an ISO language ("en", "fil"),
         * optionally followed by an ISO region ("pt-BR", "zh_CN") or a 4-letter
         * script ("sr-Latn"). Shape alone is not enough — "web", "ios" or "src"
         * must not be mistaken for languages (they used to be).
         */
        internal fun looksLikeLocale(name: String): Boolean {
            val parts = name.split('-', '_')
            if (parts.isEmpty() || parts.size > 2) return false
            if (parts[0].lowercase() !in ISO_LANGUAGES) return false
            if (parts.size == 1) return true
            val subtag = parts[1]
            return subtag.uppercase() in ISO_COUNTRIES ||
                (subtag.length == 4 && subtag.all { it.isLetter() })
        }
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
     *
     * The result is cached on the project: the annotator, completion, folding, inlay hints and
     * gutter icons all call this on every highlighting pass, and each call used to re-query the
     * file index and rebuild an element tree per translation file. The cache is dropped as soon
     * as the PSI, the project roots or the plugin configuration change (see [CacheStamps]), and
     * is held through a SoftReference so it never keeps translation files from being collected.
     *
     * A platform CachedValue is deliberately not used here: the element trees are rebuilt on
     * every computation and carry no structural equals, which the platform idempotence checker
     * reports as a non-idempotent provider in unit-test mode.
     */
    fun findAllSources(project: Project): List<LocalizationSource> {
        val config = Settings.getInstance(project).config()
        val stamps = CacheStamps(
            psi = PsiModificationTracker.getInstance(project).modificationCount,
            roots = ProjectRootManager.getInstance(project).modificationCount,
            config = config.hashCode()
        )

        cachedSources(project, stamps)?.let { return it }

        val sources = computeAllSources(project, config)
        project.putUserData(ALL_SOURCES_CACHE, SoftReference(CachedSources(stamps, sources)))
        return sources
    }

    /**
     * Returns the cached scan when it is still current, null when it must be recomputed.
     *
     * Two threads racing here recompute the same thing and the last one wins: the cached
     * value is immutable, so a duplicated scan is the only cost.
     */
    private fun cachedSources(project: Project, stamps: CacheStamps): List<LocalizationSource>? {
        val cached = project.getUserData(ALL_SOURCES_CACHE)?.get() ?: return null
        if (cached.stamps != stamps) return null
        // A file reloaded from disk can leave invalid PSI behind: handing those elements
        // out would throw PsiInvalidElementAccessException in the callers, so rescan.
        if (cached.sources.any { it.tree?.value()?.isValid == false }) return null
        return cached.sources
    }

    private fun computeAllSources(project: Project, config: Config): List<LocalizationSource> {
        val basePath = project.basePath ?: ""
        return Extensions.LOCALIZATION.extensionList.flatMap { findAllSourcesByFileType(project, it, config.translationsRoot, basePath) } +
                findSourcesByConfiguration(project)
    }

    /** Everything [findAllSources] depends on; any change invalidates the cached scan. */
    private data class CacheStamps(val psi: Long, val roots: Long, val config: Int)

    private class CachedSources(val stamps: CacheStamps, val sources: List<LocalizationSource>)

    private fun findAllSourcesByFileType(
        project: Project,
        localization: Localization<PsiElement>,
        translationsRoot: String,
        basePath: String
    ): List<LocalizationSource> {
        return ReadAction.compute<List<LocalizationSource>, RuntimeException> {
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

    private fun findSourcesByConfiguration(project: Project): List<LocalizationSource> {
        return Extensions.TECHNOLOGY.extensionList.flatMap {it.findSourcesByConfiguration(project)}
    }

    //    Finds virtual files by names and type
    private fun findVirtualFilesByName(project: Project, fileNames: List<String>): List<LocalizationSource> {
        return Extensions.LOCALIZATION.extensionList.flatMap {findSourcesByFileType(project, fileNames, it)}
    }

    private fun findSourcesByFileType(project: Project, fileNames: List<String>, localization: Localization<PsiElement>): List<LocalizationSource> {
        return ReadAction.compute<List<LocalizationSource>, RuntimeException> {
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
