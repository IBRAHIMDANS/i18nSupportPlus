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

        // ISO 15924 script codes actually seen in real locale tags (zh-Hans, sr-Latn, uz-Cyrl…).
        // The JDK exposes no registry for these — Character.UnicodeScript's names don't match the
        // four-letter codes — so, like ISO_LANGUAGES above, this is a deliberately curated subset
        // rather than the full ~200-entry registry: completeness would cost nothing here (an
        // obscure script code colliding with a real directory name is far less likely than an
        // obscure language code doing so), but a curated list documents which ones this plugin
        // has actually needed. Any 4-letter subtag not in this set is presumed not to be a
        // script — that used to be "any 4 letters", which let "my_page" pass as language "my"
        // (Burmese) plus script "Page" (#220).
        private val ISO_SCRIPTS: Set<String> = setOf(
            "Latn", "Cyrl", "Grek", "Arab", "Hebr", "Hans", "Hant", "Jpan", "Kore",
            "Deva", "Beng", "Guru", "Gujr", "Orya", "Taml", "Telu", "Knda", "Mlym",
            "Sinh", "Thai", "Laoo", "Mymr", "Khmr", "Tibt", "Mong", "Geor", "Armn",
            "Ethi", "Cher", "Cans", "Hang", "Bopo", "Hani", "Kana", "Hira", "Syrc",
            "Thaa", "Adlm", "Vaii", "Cham", "Tglg"
        )

        /**
         * True when [name] is a plausible locale code: an ISO language ("en", "fil"),
         * optionally followed by an ISO region ("pt-BR", "zh_CN") or an ISO 15924 script
         * ("sr-Latn"). Shape alone is not enough — "web", "ios" or "src" must not be mistaken
         * for languages, and neither must an arbitrary 4-letter word be mistaken for a script
         * (they both used to be).
         */
        internal fun looksLikeLocale(name: String): Boolean {
            val parts = name.split('-', '_')
            if (parts.isEmpty() || parts.size > 2) return false
            if (parts[0].lowercase() !in ISO_LANGUAGES) return false
            if (parts.size == 1) return true
            val subtag = parts[1]
            return subtag.uppercase() in ISO_COUNTRIES ||
                subtag.lowercase().replaceFirstChar { it.uppercase() } in ISO_SCRIPTS
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

    /**
     * Sources holding the requested namespaces, falling back to [Config.defaultNamespaces]
     * when none is requested.
     *
     * A key carrying no namespace (`t('dashboard.title')`) requests nothing, so the lookup
     * falls back to the default namespace — `translation` — which the localizations match
     * against the *file name*. A project laid out as `locales/fr.json` + `locales/en.json`
     * owns no such file, so nothing was ever found and every key of it was reported
     * unresolved, even though [findAllSources] reads that layout perfectly well through its
     * locale heuristic. When a key that requested no namespace finds nothing, the project-wide
     * scan is therefore used instead.
     *
     * The fallback is deliberately confined to that case: an explicit namespace matching no
     * file (`t('common:user.name')` with no `common.json`) must keep being reported as an
     * unresolved namespace rather than quietly resolving against unrelated files.
     *
     * [com.ibrahimdans.i18n.plugin.ide.I18nGutterIconProvider] used to carry a fallback of its
     * own, substituting the default namespaces *before* calling in — so it never asked for an
     * empty list and never reached this one. Its version was also wider: it fell back for an
     * explicit namespace too, which is precisely what this one must not do. That local fallback
     * has since been removed, and the gutter now goes through this rule like every other consumer.
     */
    fun findSources(fileNames: List<String>, project: Project): List<LocalizationSource> {
        val requestedNamespaces = fileNames.whenMatches { it.isNotEmpty() }
        val sources = (findVirtualFilesByName(project,
            requestedNamespaces ?: Settings.getInstance(project).config().defaultNamespaces()
        ) + findSourcesByConfiguration(project))
            .distinctBy { it.displayPath }
        if (sources.isNotEmpty() || requestedNamespaces != null) return sources
        // Cached on the project, so the extra call costs nothing per highlighting pass.
        return findAllSources(project)
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
        //
        // `isValid` touches the PSI, so it needs a read action. The four tool window callers
        // (TreeViewPanel, TableViewPanel and TranslationStatsPanel twice) reach this from a
        // pooled thread without holding one, which the platform reports as a SEVERE naming
        // the plugin. Opening it here rather than at each call site is what keeps a fifth
        // caller from reintroducing the defect, and it stays narrow on purpose: the scan
        // itself already runs its own read actions, and holding one across a full rescan
        // would block writes for longer than this check needs.
        val stillValid = ReadAction.compute<Boolean, RuntimeException> {
            cached.sources.none { it.tree?.value()?.isValid == false }
        }
        if (!stillValid) return null
        return cached.sources
    }

    private fun computeAllSources(project: Project, config: Config): List<LocalizationSource> {
        val basePath = project.basePath ?: ""
        return Extensions.LOCALIZATION.extensionList.flatMap { findAllSourcesByFileType(project, it, config, basePath) } +
                findSourcesByConfiguration(project)
    }

    /** Everything [findAllSources] depends on; any change invalidates the cached scan. */
    private data class CacheStamps(val psi: Long, val roots: Long, val config: Int)

    private class CachedSources(val stamps: CacheStamps, val sources: List<LocalizationSource>)

    private fun findAllSourcesByFileType(
        project: Project,
        localization: Localization<PsiElement>,
        config: Config,
        basePath: String
    ): List<LocalizationSource> {
        return ReadAction.compute<List<LocalizationSource>, RuntimeException> {
            val searchScope = config.searchScope(project)
            localization.types().flatMap { localizationType ->
                FileTypeIndex
                    .getFiles(localizationType.languageFileType, searchScope)
                    .filter { file -> !isExcludedPath(file, project) }
                    .mapNotNull { file ->
                        val template = moduleMatch(config, file, basePath)
                        when {
                            template != null -> file to template
                            isIncluded(file, config.translationsRoot, basePath) -> file to null
                            else -> null
                        }
                    }
                    .mapNotNull { (virtualFile, template) -> sourceOf(project, localization, virtualFile, template) }
            }
        }
    }

    /**
     * The locale and namespace a module template gives [file], or null when no module template
     * designates it. A file a template designates is a translation source whatever its name.
     */
    private fun moduleMatch(config: Config, file: VirtualFile, basePath: String): ModuleSources.Match? {
        if (config.modules.isEmpty()) return null
        val anchored = basePath.isNotEmpty() && file.path.startsWith("$basePath/")
        val path = if (anchored) file.path.removePrefix("$basePath/") else file.path
        return ModuleSources.match(config.modules, path, anchored)
    }

    private fun sourceOf(
        project: Project,
        localization: Localization<PsiElement>,
        virtualFile: VirtualFile,
        template: ModuleSources.Match?
    ): LocalizationSource? {
        val file = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        val dir = file.containingDirectory ?: return null
        return LocalizationSource(
            localization.elementsTree(file),
            file.name,
            dir.name,
            pathToRoot(file.project.basePath ?: "", dir.virtualFile.path).trim('/') + '/' + file.name,
            localization,
            locale = template?.locale,
            namespace = template?.namespace
        )
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
                        val config = Settings.getInstance(project).config()
                        sourceOf(project, localization, virtualFile, moduleMatch(config, virtualFile, project.basePath ?: ""))
                    }
            }
        }
    }
}
