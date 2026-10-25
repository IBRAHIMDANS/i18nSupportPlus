package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.NioWorkspace
import com.ibrahimdans.i18n.ParsedFormat
import com.ibrahimdans.i18n.SourceDiscovery
import com.ibrahimdans.i18n.StaticI18nRegistry
import com.ibrahimdans.i18n.TranslationSource
import com.ibrahimdans.i18n.plugin.format.ParsedNode
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationLeaves
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.hasRecognizedLocale
import com.ibrahimdans.i18n.plugin.utils.isLocaleNamedFile
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import java.nio.file.Path

/**
 * The translations [module] holds, key -> locale -> value, what [TranslationCheck] compares.
 * [module] is null for the whole project, or for the files outside every module of a project
 * declaring several.
 */
data class Scope(val module: ModuleConfig?, val translations: Map<String, Map<String, String>>)

/**
 * The translations of a whole project, found by [SourceDiscovery] as the plugin finds them: the
 * translations root, the module templates and the excluded folders of the settings apply.
 *
 * Keys are spelled as the plugin's tool window spells them ([KeySpelling]): `common:menu.home`, the
 * default namespaces left unwritten. A project declaring several modules with a root directory gets
 * one [Scope] per module, in the settings' order; with one or none, the project is one scope.
 *
 * In a Transloco project the keys of a scope file — `assets/i18n/admin/en.json`, next to the global
 * `assets/i18n/en.json` — are spelled with the scope as their first segment, `admin.title`, as the code
 * writes them: they are no global key, and a global `title` is another key.
 */
object ProjectSources {

    /** The scopes of [project], none when it holds no translation file. */
    fun read(project: Path, config: Config): List<Scope> {
        // The tool window's rule: a file with no locale anywhere in its path is no locale's.
        val found = SourceDiscovery(NioWorkspace(project), StaticI18nRegistry(CheckFormats.ALL), config).sources()
            .filter { it.hasRecognizedLocale() }
        found.firstNotNullOfOrNull { source ->
            if (source.tree != null) null
            else CheckFormats.unreadable(source.localization, project.resolve(source.displayPath.trim('/')), source.displayPath)
        }?.let { throw CannotCheck(it) }
        // What remains without a tree is a TS or JS file holding code, not a catalogue.
        val sources = found.filter { it.tree != null }
        // A module put aside has no file left to read (SourceDiscovery), and no section.
        val modules = ModuleSources.active(config.modules).filter { it.rootDirectory.isNotBlank() }
        val scopes = if (ProjectPackages.requiresAny(project, ProjectPackages.TRANSLOCO)) translocoScopes(sources) else emptyMap()
        if (modules.size < 2) return if (sources.isEmpty()) emptyList() else listOf(Scope(null, translationsOf(sources, config, scopes)))
        val owned = sources.groupBy { ModuleSources.owner(modules, ModuleSources.FilePath(it.displayPath.trim('/'), anchored = true)) }
        return (modules + null).mapNotNull { module -> owned[module]?.let { Scope(module, translationsOf(it, config, scopes)) } }
    }

    /** The plugin's `TranslationDataLoader.loadAllTranslations`, over parsed trees; [scopes] by display path. */
    private fun translationsOf(
        sources: List<TranslationSource<ParsedNode, ParsedFormat>>,
        config: Config,
        scopes: Map<String, String>,
    ): Map<String, Map<String, String>> {
        val defaultNamespaces = config.defaultNamespaces()
        val result = mutableMapOf<String, MutableMap<String, String>>()
        for (source in sources) {
            val namespace = source.namespace
                ?: if (source.isLocaleNamedFile()) defaultNamespaces.first() else source.name.substringBeforeLast('.')
            val nsPrefix = when {
                namespace !in defaultNamespaces -> namespace + KeySpelling.NAMESPACE_SEPARATOR
                else -> scopes[source.displayPath]?.let { it + config.keySeparator }.orEmpty()
            }
            TranslationLeaves.collect(config, source.tree!!, nsPrefix, source.localeLabel(), ParsedLeaves, result)
        }
        return result
    }

    /**
     * Transloco's scope files, display path → scope: a file named after a locale whose folder sits next to
     * global files named after a locale too — the plugin's `TranslocoScopes`.
     */
    private fun translocoScopes(sources: List<TranslationSource<ParsedNode, ParsedFormat>>): Map<String, String> {
        val localeNamed = sources.filter { it.isLocaleNamedFile() }
        val folderOf = { source: TranslationSource<ParsedNode, ParsedFormat> -> source.displayPath.trim('/').substringBeforeLast('/', "") }
        val globalFolders = localeNamed.mapTo(HashSet(), folderOf)
        return localeNamed.mapNotNull { source ->
            val folder = folderOf(source).takeIf { '/' in it } ?: return@mapNotNull null
            if (folder.substringBeforeLast('/') in globalFolders) source.displayPath to folder.substringAfterLast('/') else null
        }.toMap()
    }
}
