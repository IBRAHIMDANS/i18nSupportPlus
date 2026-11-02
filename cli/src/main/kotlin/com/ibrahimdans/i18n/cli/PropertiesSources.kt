package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.NioWorkspace
import com.ibrahimdans.i18n.ParsedFormat
import com.ibrahimdans.i18n.SourceDiscovery
import com.ibrahimdans.i18n.TranslationSource
import com.ibrahimdans.i18n.TranslationTechnology
import com.ibrahimdans.i18n.Workspace
import com.ibrahimdans.i18n.plugin.format.ParsedNode
import com.ibrahimdans.i18n.plugin.format.PropertiesBundle
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

/**
 * Java resource bundles (`messages_fr.properties`) as sources of a project check, as the plugin's
 * `PropertiesBundles` declares them: the locale and namespace of each file are [PropertiesBundle.placementsIn]'s,
 * the file without a suffix taking the reference locale of the module holding its folder.
 */
internal class PropertiesSources(private val config: Config) : TranslationTechnology<ParsedNode, ParsedFormat> {

    override fun translationFunctionNames(): List<String> = emptyList()

    override fun initialize(workspace: Workspace) {}

    override fun cfgNamespaces(): List<String> = emptyList()

    override fun findSourcesByConfiguration(workspace: Workspace): List<TranslationSource<ParsedNode, ParsedFormat>> {
        val nio = workspace as? NioWorkspace ?: return emptyList()
        val excluded = PropertiesBundle.EXCLUDED_DIRECTORIES + config.excludedDirectorySet()
        return nio.files(CheckFormats.PROPERTIES.extensions)
            .map { Path.of(it.path) }
            .filterNot { SourceDiscovery.isExcludedPath(relative(nio, it), excluded) }
            .groupBy { it.parent }
            .flatMap { (_, files) ->
                val module = ModuleSources.owner(config.modules, ModuleSources.FilePath(relative(nio, files.first()), anchored = true))
                val placements = PropertiesBundle.placementsIn(files.map { it.nameWithoutExtension }, ReferenceLocale.wanted(module, config))
                files.mapNotNull { file ->
                    val placement = placements[file.nameWithoutExtension] ?: return@mapNotNull null
                    val tree = try {
                        CheckFormats.PROPERTIES.parse(Files.readString(file))
                    } catch (e: IOException) {
                        null
                    }
                    TranslationSource(
                        tree, file.fileName.toString(), file.parent.fileName.toString(), relative(nio, file),
                        CheckFormats.PROPERTIES, locale = placement.locale, namespace = placement.namespace,
                    )
                }
            }
    }

    private fun relative(nio: NioWorkspace, file: Path): String =
        file.toString().replace('\\', '/').removePrefix(nio.basePath).trim('/')
}
