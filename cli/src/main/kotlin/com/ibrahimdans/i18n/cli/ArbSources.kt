package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.NioWorkspace
import com.ibrahimdans.i18n.ParsedFormat
import com.ibrahimdans.i18n.SourceDiscovery
import com.ibrahimdans.i18n.TranslationSource
import com.ibrahimdans.i18n.TranslationTechnology
import com.ibrahimdans.i18n.Workspace
import com.ibrahimdans.i18n.plugin.format.ArbCatalogue
import com.ibrahimdans.i18n.plugin.format.ParsedNode
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

/**
 * Flutter's ARB catalogues as sources of a project check, as the plugin's `ArbCatalogues` declares
 * them: every `.arb` file outside the excluded folders, with the locale it holds ([ArbCatalogue.localeOf])
 * and the default namespace. A catalogue whose locale is found nowhere is left out.
 */
internal class ArbSources(private val config: Config) : TranslationTechnology<ParsedNode, ParsedFormat> {

    override fun translationFunctionNames(): List<String> = emptyList()

    override fun initialize(workspace: Workspace) {}

    override fun cfgNamespaces(): List<String> = emptyList()

    override fun findSourcesByConfiguration(workspace: Workspace): List<TranslationSource<ParsedNode, ParsedFormat>> {
        val nio = workspace as? NioWorkspace ?: return emptyList()
        // Where `flutter` writes what it builds: catalogues copied there are no sources.
        val excluded = SourceDiscovery.DEFAULT_EXCLUDED_DIRECTORIES + config.excludedDirectorySet() + ".dart_tool"
        return nio.files(CheckFormats.ARB.extensions).mapNotNull { file ->
            val relative = file.path.removePrefix(nio.basePath).trim('/')
            if (SourceDiscovery.isExcludedPath(relative, excluded)) return@mapNotNull null
            val path = Path.of(file.path)
            val tree = CheckFormats.ARB.parse(TextFiles.read(path, relative))
            val locale = ArbCatalogue.localeOf(path.nameWithoutExtension, tree?.let(ArbCatalogue::declaredLocale)) ?: return@mapNotNull null
            TranslationSource(tree, file.name, path.parent.fileName.toString(), relative, CheckFormats.ARB, locale = locale)
        }
    }
}
