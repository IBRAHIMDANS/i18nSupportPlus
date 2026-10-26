package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.IdeI18nRegistry
import com.ibrahimdans.i18n.Localization
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.SourceDiscovery
import com.ibrahimdans.i18n.SourceWorkspace
import com.ibrahimdans.i18n.WorkspaceFile
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.settings.searchScope
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
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
        private val TYPED_SOURCES_CACHE =
            Key.create<SoftReference<TypedSources>>("i18n.localization.typedSources")

        private val CONFIGURED_SOURCES_CACHE =
            Key.create<SoftReference<ConfiguredSources>>("i18n.localization.configuredSources")

        private val COMPOSED_SOURCES_CACHE =
            Key.create<SoftReference<ComposedSources>>("i18n.localization.composedSources")

        /** See [SourceDiscovery.isExcludedPath]. */
        internal fun isExcludedPath(path: String, exclusions: Set<String>): Boolean =
            SourceDiscovery.isExcludedPath(path, exclusions)

        /** See [LocaleNames.looksLikeLocale]. */
        internal fun looksLikeLocale(name: String): Boolean = LocaleNames.looksLikeLocale(name)

        /** See [SourceDiscovery.isIncluded]. */
        internal fun isIncluded(file: VirtualFile, translationsRoot: String, basePath: String): Boolean =
            SourceDiscovery.isIncluded(file.path, translationsRoot, basePath)

        /** See [SourceDiscovery.isUnderRoot]. */
        internal fun isUnderRoot(file: VirtualFile, translationsRoot: String, basePath: String): Boolean =
            SourceDiscovery.isUnderRoot(file.path, translationsRoot, basePath)
    }

    /** The rules of discovery, on [project] as it stands with [config]: nothing in it is cached. */
    private fun discovery(project: Project, config: Config) =
        SourceDiscovery(IdeSourceWorkspace(project, config), IdeI18nRegistry, config)

    /**
     * Sources holding the requested namespaces, falling back to [Config.defaultNamespaces]
     * when none is requested, then to [findAllSources] for a key that requested none and found
     * nothing — the rule is [SourceDiscovery.select].
     *
     * [com.ibrahimdans.i18n.plugin.ide.I18nGutterIconProvider] used to carry a fallback of its
     * own, substituting the default namespaces *before* calling in — so it never asked for an
     * empty list and never reached this one. Its version was also wider: it fell back for an
     * explicit namespace too, which is precisely what this one must not do. That local fallback
     * has since been removed, and the gutter now goes through this rule like every other consumer.
     */
    fun findSources(fileNames: List<String>, project: Project): List<LocalizationSource> {
        // Every key of every feature asks this on each highlighting pass, and each lookup scanned
        // the file index and rebuilt the trees of the files it kept: measured at ~4.6 ms a call
        // with 500 translation files, ~16x a cached answer. Cached per namespace list (see
        // [composedSources] for what drops it).
        val cache = composedSources(project)
        cache.byNames[fileNames]?.let { cached -> if (allValid(cached)) return cached }
        return computeSources(fileNames, project).also { cache.byNames[fileNames] = it }
    }

    private fun computeSources(fileNames: List<String>, project: Project): List<LocalizationSource> {
        val config = Settings.getInstance(project).config()
        // The whole scan is cached on the project, so the fallback costs nothing per highlighting pass.
        return discovery(project, config).select(
            fileNames,
            { namespaces -> typedSourcesByName(project, config, namespaces) },
            configuredSources(project, config),
        ) { findAllSources(project) }
    }

    /**
     * [findSources] for a key written in [caller]'s file: in a monorepo, restricted to the module
     * whose `rootDirectory` holds that file.
     *
     * Two modules each owning a `common.json` used to be one pool: `t('common:title')` in
     * `apps/admin` resolved against `apps/web`'s file too, so a key missing from its own module was
     * never reported, and navigation and hints mixed both. A file in no module, a project without
     * modules, and a module whose translations all live outside its root directory (a shared
     * package) keep the project-wide result. A file of a module put aside resolves nothing: the
     * other applications' files are not its own, and its own are no source.
     */
    fun findSources(fileNames: List<String>, caller: PsiElement): List<LocalizationSource> {
        if (isPutAside(caller)) return emptyList()
        return componentSources(fileNames, caller) + projectSources(fileNames, caller)
    }

    /** Whether [caller]'s file lies in a module put aside ([com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig.enabled]). */
    private fun isPutAside(caller: PsiElement): Boolean {
        val modules = Settings.getInstance(caller.project).config().modules
        if (modules.none { !it.enabled }) return false
        val file = caller.hostVirtualFile() ?: return false
        return ModuleSources.isPutAside(modules, projectPath(file, caller.project.basePath ?: ""))
    }

    /**
     * The sources to *read* [fullKey] from, for a key written in [caller]'s file: its own namespaces'
     * ([FullKey.allNamespaces]) — or, only when none of them holds the key, those of the first of
     * its [FullKey.fallbackNamespaces] that does, as i18next's `fallbackNS` tries them in order.
     * A key its own namespace holds never reaches the fallback, so its reference stays single and
     * a hint shows that namespace's value. Writing goes through [findSources] on the own namespaces.
     */
    fun findReadSources(fullKey: FullKey, caller: PsiElement): List<LocalizationSource> {
        val own = findSources(fullKey.allNamespaces(), caller)
        if (fullKey.fallbackNamespaces.isEmpty()) return own
        val pluralSeparator = Settings.getInstance(caller.project).config().pluralSeparator
        fun holdsKey(source: LocalizationSource) =
            FallbackLookup.resolve(fullKey.compositeKey, source, pluralSeparator).any { it.unresolved.isEmpty() && it.element != null }
        if (own.any(::holdsKey)) return own
        return fullKey.fallbackNamespaces.asSequence()
            .map { findSources(listOf(it), caller) }
            .firstOrNull { sources -> sources.any(::holdsKey) }
            ?: own
    }

    private object FallbackLookup : CompositeKeyResolver<PsiElement>

    /**
     * The sources the component holding [caller] declares for itself — vue-i18n's `<i18n>` block —
     * when the key is asked of a default namespace, which is the only one a block declares. They
     * come first: the framework resolves a component's own messages before the project's.
     */
    private fun componentSources(fileNames: List<String>, caller: PsiElement): List<LocalizationSource> {
        val providers = Extensions.COMPONENT_SOURCES.extensionList
        if (providers.isEmpty()) return emptyList()
        val defaults = Settings.getInstance(caller.project).config().defaultNamespaces()
        if (fileNames.isNotEmpty() && fileNames.none { it in defaults }) return emptyList()
        return providers.flatMap { it.sourcesFor(caller) }
    }

    private fun projectSources(fileNames: List<String>, caller: PsiElement): List<LocalizationSource> {
        val project = caller.project
        val config = Settings.getInstance(project).config()
        val callerFile = caller.hostVirtualFile()
        val module = if (config.modules.isEmpty() || callerFile == null) null
        else ModuleSources.owner(ModuleSources.active(config.modules), projectPath(callerFile, project.basePath ?: ""))
        return findSources(fileNames, project, module)
    }

    /**
     * [findSources] restricted to [module]'s root directory, for a view that already knows its
     * module — a tool window tab — rather than a code file to take it from. No module, or one whose
     * translations all live outside its root directory (a shared package), keeps the project-wide
     * result, as for a caller.
     */
    fun findSources(fileNames: List<String>, project: Project, module: ModuleConfig?): List<LocalizationSource> {
        val sources = findSources(fileNames, project)
        if (module == null) return sources
        val basePath = project.basePath ?: ""

        fun inModule(source: LocalizationSource): Boolean {
            val file = (source.tree?.value() ?: source.host)?.containingFile?.virtualFile ?: return false
            return ModuleSources.contains(module, projectPath(file, basePath))
        }
        val scoped = sources.filter(::inModule)
        // Asked of the cached scan only when this lookup found nothing inside the module.
        if (scoped.isEmpty() && findAllSources(project).none(::inModule)) return sources
        return scoped
    }

    /**
     * The files named after one of [fileNames], whatever the configuration says.
     *
     * The annotator asks this for every key naming a namespace, on every pass: uncached, it scanned
     * the file index and rebuilt a tree per matching file each time — measured at 290–540 ms a pass
     * for 100 such keys over 500 translation files (`HighlightingPerformanceTest`, scenario e).
     * Cached with the other lookups of the file index (see [typedSources]).
     */
    fun findNamespaceFiles(fileNames: List<String>, project: Project): List<LocalizationSource> {
        if (fileNames.isEmpty()) return emptyList()
        val config = Settings.getInstance(project).config()
        // A technology may declare the namespace of a file not named after it: a Transloco scope.
        return discovery(project, config).namespaceFiles(
            fileNames, typedSourcesByName(project, config, fileNames), configuredSources(project, config)
        )
    }

    /**
     * Finds all localization sources in the project regardless of filename.
     * Used by the table/tree views to display all translations, including projects
     * with multiple namespaces (common.json, auth.json, etc.) or locale-named files (en.json).
     *
     * Which files are sources, and with which locale and namespace, is [SourceDiscovery]'s rule:
     * under [Config.translationsRoot] when one is set, a locale-looking folder or name otherwise, or
     * wherever a module template designates.
     *
     * The result is cached on the project: the annotator, completion, folding, inlay hints and
     * gutter icons all call this on every highlighting pass, and each call used to re-query the
     * file index and rebuild an element tree per translation file. See [composedSources] for what
     * drops it; every cache here is held through a SoftReference so it never keeps translation
     * files from being collected.
     *
     * A platform CachedValue is deliberately not used here: the element trees are rebuilt on
     * every computation and carry no structural equals, which the platform idempotence checker
     * reports as a non-idempotent provider in unit-test mode.
     */
    fun findAllSources(project: Project): List<LocalizationSource> {
        val cache = composedSources(project)
        cache.all?.let { cached -> if (allValid(cached)) return cached }
        val config = Settings.getInstance(project).config()
        return discovery(project, config).compose(typedAllSources(project, config), configuredSources(project, config))
            .also { cache.all = it }
    }

    /*
     * Three caches, because the sources come from two places that change at different times.
     *
     * - [typedSources]: what the file index finds by Localization file type — the expensive part,
     *   one scan per namespace list. Stamped with [TranslationModificationTracker], so it survives
     *   a keystroke in a component.
     * - [configuredSources]: what the technologies declare (a TS catalog, an i18next configuration).
     *   Those live in JS/TS files, so they stay stamped with the project-wide PSI count: recomputed
     *   once after a keystroke, not once per namespace as when everything shared that stamp.
     * - [composedSources]: the answers handed out, the two put together. Stamped with both, so a
     *   caller keeps getting the same instance while nothing moved — callers compare sources by
     *   identity — and putting them together again costs no scan.
     *
     * Two threads racing on any of them recompute the same thing and the last one wins: cached
     * values are immutable, so a duplicated computation is the only cost.
     */

    private fun typedSources(project: Project, config: Config): TypedSources {
        val stamps = CacheStamps(TranslationModificationTracker.getInstance(project).modificationCount, project, config)
        return project.getUserData(TYPED_SOURCES_CACHE)?.get()?.takeIf { it.stamps == stamps }
            ?: TypedSources(stamps).also { project.putUserData(TYPED_SOURCES_CACHE, SoftReference(it)) }
    }

    private fun typedSourcesByName(project: Project, config: Config, fileNames: List<String>): List<LocalizationSource> {
        val cache = typedSources(project, config)
        cache.byNames[fileNames]?.let { cached -> if (allValid(cached)) return cached }
        return ReadAction.compute<List<LocalizationSource>, RuntimeException> { discovery(project, config).namedSources(fileNames) }
            .also { cache.byNames[fileNames] = it }
    }

    private fun typedAllSources(project: Project, config: Config): List<LocalizationSource> {
        val cache = typedSources(project, config)
        cache.all?.let { cached -> if (allValid(cached)) return cached }
        return ReadAction.compute<List<LocalizationSource>, RuntimeException> { discovery(project, config).scannedSources() }
            .also { cache.all = it }
    }

    private fun configuredSources(project: Project, config: Config): List<LocalizationSource> {
        val stamps = configuredStamps(project, config)
        project.getUserData(CONFIGURED_SOURCES_CACHE)?.get()
            ?.takeIf { it.stamps == stamps && allValid(it.sources) }
            ?.let { return it.sources }
        return discovery(project, config).configuredSources()
            .also { project.putUserData(CONFIGURED_SOURCES_CACHE, SoftReference(ConfiguredSources(stamps, it))) }
    }

    private fun composedSources(project: Project): ComposedSources {
        val config = Settings.getInstance(project).config()
        val stamps = CacheStamps(TranslationModificationTracker.getInstance(project).modificationCount, project, config) to
            configuredStamps(project, config)
        return project.getUserData(COMPOSED_SOURCES_CACHE)?.get()?.takeIf { it.stamps == stamps }
            ?: ComposedSources(stamps).also { project.putUserData(COMPOSED_SOURCES_CACHE, SoftReference(it)) }
    }

    private fun configuredStamps(project: Project, config: Config) =
        CacheStamps(PsiModificationTracker.getInstance(project).modificationCount, project, config)

    /**
     * A file reloaded from disk can leave invalid PSI behind: handing those elements out would throw
     * PsiInvalidElementAccessException in the callers, so a cached answer holding one is recomputed.
     *
     * `isValid` touches the PSI, so it needs a read action. The four tool window callers
     * (TreeViewPanel, TableViewPanel and TranslationStatsPanel twice) reach [findAllSources] from a
     * pooled thread without holding one, which the platform reports as a SEVERE naming the plugin.
     * Opening it here rather than at each call site is what keeps a fifth caller from reintroducing
     * the defect, and it stays narrow on purpose: the scan itself already runs its own read actions,
     * and holding one across a full rescan would block writes for longer than this check needs.
     */
    private fun allValid(sources: List<LocalizationSource>): Boolean =
        ReadAction.compute<Boolean, RuntimeException> { sources.none { it.tree?.value()?.isValid == false } }

    /** What a cached answer depends on: [changes] (a modification count), the project roots and the configuration. */
    private data class CacheStamps(val changes: Long, val roots: Long, val config: Int) {
        constructor(changes: Long, project: Project, config: Config) :
            this(changes, ProjectRootManager.getInstance(project).modificationCount, config.hashCode())
    }

    private class TypedSources(val stamps: CacheStamps) {
        @Volatile var all: List<LocalizationSource>? = null
        val byNames = java.util.concurrent.ConcurrentHashMap<List<String>, List<LocalizationSource>>()
    }

    private class ConfiguredSources(val stamps: CacheStamps, val sources: List<LocalizationSource>)

    /** [findSources] and [findAllSources] answers for one pair of stamps (typed, configured). */
    private class ComposedSources(val stamps: Pair<CacheStamps, CacheStamps>) {
        @Volatile var all: List<LocalizationSource>? = null
        val byNames = java.util.concurrent.ConcurrentHashMap<List<String>, List<LocalizationSource>>()
    }

    private fun projectPath(file: VirtualFile, basePath: String) = ModuleSources.FilePath.of(file, basePath)
}

/**
 * [project] as [SourceDiscovery] reads it: the files of a [Localization] come from the file index
 * within the configured search scope, the IDE's excluded folders are left out, and a file is read
 * through its PSI. Still an [IdeWorkspace], as the technologies it is handed expect.
 */
private class IdeSourceWorkspace(project: Project, config: Config) :
    IdeWorkspace(project), SourceWorkspace<PsiElement, Localization<PsiElement>> {

    // Only a scan needs them, and it runs in a read action: the composition of cached lists does not.
    private val searchScope by lazy { config.searchScope(project) }
    private val fileIndex by lazy { ProjectFileIndex.getInstance(project) }
    private val psiManager by lazy { PsiManager.getInstance(project) }

    override fun files(format: Localization<PsiElement>): List<WorkspaceFile> =
        format.types().flatMap { type -> FileTypeIndex.getFiles(type.languageFileType, searchScope).map(::IdeWorkspaceFile) }

    // A format of several types (JSON and JSON5) names a file after the extensions of its own type.
    override fun isNamedAfter(format: Localization<PsiElement>, file: WorkspaceFile, fileNames: List<String>): Boolean =
        format.types().any { type -> format.matches(type, file, fileNames) }

    /** IntelliJ's own exclusions: `.gitignore`d and excluded folders. */
    override fun isExcluded(file: WorkspaceFile): Boolean = fileIndex.isExcluded(file.virtualFile)

    // The project's own folder, when the IDE holds it as content: a light test project has none,
    // and nothing is foreign there.
    private val home by lazy { project.guessProjectDir()?.takeIf { fileIndex.isInContent(it) } }

    /** Outside the project's own folder: the content root of an attached project. */
    override fun isForeign(file: WorkspaceFile): Boolean {
        val home = home ?: return false
        return !VfsUtilCore.isAncestor(home, file.virtualFile, false)
    }

    override fun isReadable(file: WorkspaceFile): Boolean = psiManager.findFile(file.virtualFile)?.containingDirectory != null

    override fun read(format: Localization<PsiElement>, file: WorkspaceFile): Tree<PsiElement>? =
        psiManager.findFile(file.virtualFile)?.let(format::elementsTree)
}
