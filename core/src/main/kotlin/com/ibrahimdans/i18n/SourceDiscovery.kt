package com.ibrahimdans.i18n

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.LocaleNames
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.pathToRoot

/**
 * A [Workspace] whose files [SourceDiscovery] can list and read: the plugin asks the IDE's file
 * index and PSI, [NioWorkspace] walks a folder and parses it.
 *
 * [N] is the node type of the trees it reads, [F] the formats it lists files for.
 */
interface SourceWorkspace<N, F : TranslationFormat> : Workspace {

    /** The files [format] may read, told by their type alone: no rule of [SourceDiscovery] is applied yet. */
    fun files(format: F): Collection<WorkspaceFile>

    /** Whether [file] is named after one of [fileNames] the way [format] names its files: `common.json` for `common`. */
    fun isNamedAfter(format: F, file: WorkspaceFile, fileNames: List<String>): Boolean

    /**
     * Whether the workspace itself leaves [file] out — the IDE's excluded folders. The directories the
     * configuration excludes are [SourceDiscovery]'s rule, applied on top.
     */
    fun isExcluded(file: WorkspaceFile): Boolean = false

    /**
     * Whether [file] belongs to another project than the workspace's own: an attached project, opened
     * beside it in the same window. Such a file is no source once modules describe the project.
     */
    fun isForeign(file: WorkspaceFile): Boolean = false

    /** False when [file] cannot be read at all — no source is made of it. */
    fun isReadable(file: WorkspaceFile): Boolean = true

    /**
     * The tree [format] reads in [file], or null when it finds no translations there. The file is a
     * source all the same: a file that does not parse is listed, with no tree.
     */
    fun read(format: F, file: WorkspaceFile): Tree<N>?
}

/**
 * Which files of a project are its translation sources, and the locale and namespace of each: the
 * rules every feature goes through — annotation, completion, tool window, a command-line check.
 *
 * - **Exclusions**: [DEFAULT_EXCLUDED_DIRECTORIES] and the configured ones ([isExcludedPath]), and the
 *   root directories of the modules put aside ([ModuleSources.isPutAside]).
 * - **Modules**: a file a module's path template designates is a source whatever its name, with the
 *   locale and namespace the template states ([ModuleSources]).
 * - **Root**: with a translations root set, only files under it; otherwise a file whose folder or
 *   name looks like a locale ([isIncluded]).
 * - **Namespaces**: a lookup by namespace takes the files named after it, falling back to the
 *   default namespaces, then to every source for a key naming none ([sources] with names).
 * - **Technologies**: the sources a framework's configuration declares (`findSourcesByConfiguration`)
 *   come with the rest, and a file they state a locale or a namespace for is listed once, as they state it.
 *
 * Nothing is cached: the plugin's `LocalizationSourceService` caches the parts — [scannedSources],
 * [namedSources], [configuredSources] — and puts them together with [compose], [select] and
 * [namespaceFiles].
 *
 * Outside the IDE:
 * ```
 * val discovery = SourceDiscovery(NioWorkspace(root), StaticI18nRegistry(ParsedFormat.entries), config)
 * discovery.sources()               // every source, trees parsed by JsonTranslationTree / YamlTranslationTree
 * discovery.sources(listOf("common")) // the sources of the `common` namespace
 * ```
 */
class SourceDiscovery<N, F : TranslationFormat>(
    private val workspace: SourceWorkspace<N, F>,
    private val registry: I18nRegistry<N, F>,
    private val config: Config,
) {

    /**
     * The directory names [isExcludedPath] skips. Built once per discovery, not once per file: rebuilt
     * for each of the hundreds of files a scan visits, it was a quarter of the plugin's own
     * highlighting time in a JFR profile of `HighlightingPerformanceTest`.
     */
    private val exclusions: Set<String> = DEFAULT_EXCLUDED_DIRECTORIES + config.excludedDirectorySet()

    /** Whether active modules with a root directory describe the project. */
    private val describedByModules: Boolean = ModuleSources.active(config.modules).any { it.rootDirectory.isNotBlank() }

    /** [Config.localeAliasMap], read once per discovery for the same reason. */
    private val aliases: Map<String, String> = config.localeAliasMap()

    /** Every translation source of the workspace: what [scannedSources] finds, with the [configuredSources]. */
    fun sources(): List<TranslationSource<N, F>> = compose(scannedSources(), configuredSources())

    /**
     * The sources holding [fileNames], the namespaces a key requests — see [select].
     * [TranslationSource.displayPath] is project-relative for a file under [Workspace.basePath].
     */
    fun sources(fileNames: List<String>): List<TranslationSource<N, F>> =
        select(fileNames, ::namedSources, configuredSources(), ::sources)

    /** The files named after one of [fileNames], whatever the configuration says — see [namespaceFiles]. */
    fun namespaceFiles(fileNames: List<String>): List<TranslationSource<N, F>> =
        if (fileNames.isEmpty()) emptyList() else namespaceFiles(fileNames, namedSources(fileNames), configuredSources())

    /**
     * Every file of a format that is a source by itself: one a module template designates, or one the
     * root (or, without a root, the locale guess) takes in.
     */
    fun scannedSources(): List<TranslationSource<N, F>> =
        registry.formats().flatMap { format ->
            workspace.files(format)
                .filter { file -> !isExcluded(file) }
                .mapNotNull { file ->
                    val template = moduleMatch(file)
                    val alias = aliasMatch(file)
                    when {
                        template != null -> sourceOf(format, file, template)
                        isIncluded(file.path, config.translationsRoot, workspace.basePath) -> sourceOf(format, file, alias)
                        // An aliased name is a locale for the guess too.
                        alias != null && config.translationsRoot.isBlank() -> sourceOf(format, file, alias)
                        else -> null
                    }
                }
        }

    /**
     * The files of a format named after one of [fileNames]. The configured root bounds this lookup as
     * it bounds [scannedSources]: any `common.json` in the project — a serverless config, a fixture —
     * used to be a source of the `common` namespace, shown as a locale named after the file.
     */
    fun namedSources(fileNames: List<String>): List<TranslationSource<N, F>> =
        registry.formats().flatMap { format ->
            workspace.files(format)
                // Matched on the name first: it is the cheap test, and it discards nearly every file.
                .filter { file -> workspace.isNamedAfter(format, file, fileNames) && !isExcluded(file) }
                .mapNotNull { file ->
                    val template = moduleMatch(file)
                    if (template == null && config.translationsRoot.isNotBlank() &&
                        !isUnderRoot(file.path, config.translationsRoot, workspace.basePath)
                    ) null
                    else sourceOf(format, file, template ?: aliasMatch(file))
                }
        }.distinctBy { it.displayPath }

    /** The sources the technologies' configurations declare: a TS catalog, an i18next configuration. */
    fun configuredSources(): List<TranslationSource<N, F>> =
        registry.technologies().flatMap { it.findSourcesByConfiguration(workspace) }

    /**
     * [scanned] and [configured] as one list. A file whose namespace or locale a technology states is
     * listed once, as it states it: the locale guess alone would also read a Transloco scope file as the
     * default namespace, and a Paraglide message file twice — so `en.json` found itself as its
     * reference locale's file.
     */
    fun compose(scanned: List<TranslationSource<N, F>>, configured: List<TranslationSource<N, F>>): List<TranslationSource<N, F>> {
        val claimed = configured.filter { it.namespace != null || it.locale != null }.mapTo(HashSet()) { it.displayPath }
        return scanned.filter { it.displayPath !in claimed } + configured
    }

    /**
     * The sources holding the requested namespaces [fileNames], falling back to [Config.defaultNamespaces]
     * when none is requested; [named] looks files up by name, [all] lists every source.
     *
     * A key carrying no namespace (`t('dashboard.title')`) requests nothing, so the lookup falls back to
     * the default namespace — `translation` — which the formats match against the *file name*. A project
     * laid out as `locales/fr.json` + `locales/en.json` owns no such file: when a key that requested no
     * namespace finds nothing, every source is used instead.
     *
     * The fallback is deliberately confined to that case: an explicit namespace matching no file
     * (`t('common:user.name')` with no `common.json`) must keep being reported as an unresolved
     * namespace rather than quietly resolving against unrelated files.
     */
    fun select(
        fileNames: List<String>,
        named: (List<String>) -> List<TranslationSource<N, F>>,
        configured: List<TranslationSource<N, F>>,
        all: () -> List<TranslationSource<N, F>>,
    ): List<TranslationSource<N, F>> {
        val requested = fileNames.takeIf { it.isNotEmpty() }
        val namespaces = requested ?: config.defaultNamespaces()
        val sources = (named(namespaces) + configured.filter { it.namespace == null || it.namespace in namespaces })
            .distinctBy { it.displayPath }
        if (sources.isNotEmpty() || requested != null) return sources
        return all()
    }

    /**
     * The files [named] after one of [fileNames], with the [configured] sources a technology declares
     * for one of them although the file is named otherwise: a Transloco scope. [named] itself when
     * there are none, which callers caching it compare by identity.
     */
    fun namespaceFiles(
        fileNames: List<String>,
        named: List<TranslationSource<N, F>>,
        configured: List<TranslationSource<N, F>>,
    ): List<TranslationSource<N, F>> {
        val declared = configured.filter { it.namespace != null && it.namespace in fileNames }
        return if (declared.isEmpty()) named else named + declared
    }

    /**
     * Checked on the project-relative path: a project kept in a folder named like an excluded one
     * (`~/build/shop`) would otherwise lose every file.
     */
    private fun isExcluded(file: WorkspaceFile): Boolean {
        val path = ModuleSources.FilePath.of(file.path, workspace.basePath)
        return workspace.isExcluded(file) || isExcludedPath(path.path, exclusions) ||
            // A module put aside takes its files out as an excluded directory would.
            ModuleSources.isPutAside(config.modules, path) ||
            // Modules describe the project: an attached project's files are none of its translations.
            (describedByModules && workspace.isForeign(file))
    }

    /**
     * The locale and namespace an alias of the settings gives [file]: `gb.json` is the `gb` locale's,
     * `gb/common.json` its `common` namespace — stated as a template states them, so every rule reading
     * a source's locale (`localeLabel`, `hasRecognizedLocale`) takes it without knowing the aliases.
     * Null when neither its name nor its folder is an alias.
     */
    private fun aliasMatch(file: WorkspaceFile): ModuleSources.Match? {
        if (aliases.isEmpty()) return null
        val stem = file.nameWithoutExtension
        if (stem in aliases) return ModuleSources.Match(stem, null)
        val folder = file.path.substringBeforeLast('/', "").substringAfterLast('/')
        if (folder in aliases && !LocaleNames.looksLikeLocale(stem)) return ModuleSources.Match(folder, stem)
        return null
    }

    /**
     * The locale and namespace a module template gives [file], or null when no module template
     * designates it. A file a template designates is a translation source whatever its name.
     */
    private fun moduleMatch(file: WorkspaceFile): ModuleSources.Match? {
        if (config.modules.isEmpty()) return null
        return ModuleSources.match(ModuleSources.active(config.modules), ModuleSources.FilePath.of(file.path, workspace.basePath))
    }

    private fun sourceOf(format: F, file: WorkspaceFile, template: ModuleSources.Match?): TranslationSource<N, F>? {
        if (!workspace.isReadable(file)) return null
        val directory = file.path.substringBeforeLast('/', "")
        return TranslationSource(
            workspace.read(format, file),
            file.name,
            directory.substringAfterLast('/'),
            pathToRoot(workspace.basePath, directory).trim('/') + '/' + file.name,
            format,
            locale = template?.locale,
            namespace = template?.namespace
        )
    }

    companion object {

        // `.i18n-reviews` (ReviewLocation.DIRECTORY) holds the review files, `fr.json` one per locale:
        // read as sources, each would be taken for a translation of its locale.
        /** The directories never searched for translations, whatever the configuration adds. */
        val DEFAULT_EXCLUDED_DIRECTORIES: Set<String> = setOf(
            "node_modules", "build", "dist", ".next", "out",
            "storybook-static", ".nuxt", ".output", "coverage", ".cache", "vendor", ".i18n-reviews"
        )

        /**
         * True when [path] crosses one of the [exclusions] directories.
         *
         * `vendor` is Composer's dependency folder, but `lang/vendor/<package>/` is also where Laravel
         * puts a project's own overrides of a package's translations (`resources/lang/vendor/` before
         * Laravel 9): a `vendor` whose parent is `lang` is the project's, and is kept. Only that one
         * directory is spared — a `lang` folder inside Composer's `vendor/` stays excluded, since the
         * `vendor` above it still matches.
         */
        fun isExcludedPath(path: String, exclusions: Set<String>): Boolean {
            val segments = path.split('/')
            return segments.indices.any { index ->
                segments[index] in exclusions && !isLaravelPackageOverrides(segments, index)
            }
        }

        private fun isLaravelPackageOverrides(segments: List<String>, index: Int): Boolean =
            index > 0 && segments[index] == "vendor" && segments[index - 1] == "lang"

        /**
         * Whether the file at [path] is taken in by the scan of all sources:
         * - with a [translationsRoot], only a file under basePath/[translationsRoot] ([isUnderRoot]);
         * - without one, a file whose parent folder or name without extension looks like a locale code.
         */
        fun isIncluded(path: String, translationsRoot: String, basePath: String): Boolean {
            if (translationsRoot.isNotBlank()) return isUnderRoot(path, translationsRoot, basePath)
            if ('/' !in path) return false
            val directory = path.substringBeforeLast('/')
            val name = path.substringAfterLast('/')
            val stem = name.lastIndexOf('.').takeIf { it >= 0 }?.let { name.substring(0, it) } ?: name
            return LocaleNames.looksLikeLocale(directory.substringAfterLast('/')) || LocaleNames.looksLikeLocale(stem)
        }

        /** True when [path] lies inside basePath/[translationsRoot] — `locales2/` is not under `locales`. */
        fun isUnderRoot(path: String, translationsRoot: String, basePath: String): Boolean {
            val rootPath = "$basePath/$translationsRoot".trimEnd('/')
            return path == rootPath || path.startsWith("$rootPath/")
        }
    }
}
