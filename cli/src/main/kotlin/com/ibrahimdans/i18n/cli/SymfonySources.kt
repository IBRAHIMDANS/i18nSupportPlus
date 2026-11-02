package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.NioWorkspace
import com.ibrahimdans.i18n.ParsedFormat
import com.ibrahimdans.i18n.SourceDiscovery
import com.ibrahimdans.i18n.TranslationSource
import com.ibrahimdans.i18n.TranslationTechnology
import com.ibrahimdans.i18n.Workspace
import com.ibrahimdans.i18n.plugin.format.ParsedNode
import com.ibrahimdans.i18n.plugin.format.SymfonyCatalogue
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Symfony's catalogues as sources of a project check, as the plugin's `SymfonyTranslations` declares
 * them: in a project whose `composer.json` requires `symfony/translation`, every file of a
 * `translations/` folder named `<domain>[+intl-icu].<locale>.<format>` ([SymfonyCatalogue]), with that
 * locale and the domain as namespace. YAML, XLIFF 1.2 and 2.0, PHP, JSON and PO are read.
 *
 * Keys are spelled whole, so a key nested under `user:` and one written `user.name` in a flat
 * catalogue — `translation:extract`'s default — are the same key, as Symfony reads them.
 */
internal class SymfonySources(private val config: Config) : TranslationTechnology<ParsedNode, ParsedFormat> {

    override fun translationFunctionNames(): List<String> = emptyList()

    override fun initialize(workspace: Workspace) {}

    override fun cfgNamespaces(): List<String> = emptyList()

    override fun findSourcesByConfiguration(workspace: Workspace): List<TranslationSource<ParsedNode, ParsedFormat>> {
        val nio = workspace as? NioWorkspace ?: return emptyList()
        if (!ProjectPackages.composerRequiresAny(Path.of(nio.basePath), listOf(TRANSLATION_PACKAGE))) return emptyList()
        val excluded = SourceDiscovery.DEFAULT_EXCLUDED_DIRECTORIES + config.excludedDirectorySet()
        return nio.files(FORMATS.keys.toList()).mapNotNull { file ->
            val path = Path.of(file.path)
            if (path.parent?.fileName?.toString() != SymfonyCatalogue.FOLDER) return@mapNotNull null
            val relative = file.path.replace('\\', '/').removePrefix(nio.basePath).trim('/')
            if (SourceDiscovery.isExcludedPath(relative, excluded)) return@mapNotNull null
            val catalogue = SymfonyCatalogue.parse(file.name) ?: return@mapNotNull null
            val format = FORMATS.getValue(file.name.substringAfterLast('.').lowercase())
            val tree = try {
                format.parse(Files.readString(path))
            } catch (e: IOException) {
                null
            }
            TranslationSource(
                tree, file.name, SymfonyCatalogue.FOLDER, relative, format,
                locale = catalogue.locale, namespace = catalogue.domain,
            )
        }
    }

    private companion object {
        const val TRANSLATION_PACKAGE = "symfony/translation"

        /** The formats of Symfony's loaders the check reads, by extension: `.csv` and `.ini` are left out. */
        val FORMATS: Map<String, ParsedFormat> = mapOf(
            "yaml" to ParsedFormat.YAML, "yml" to ParsedFormat.YAML,
            "xlf" to CheckFormats.XLIFF, "xliff" to CheckFormats.XLIFF,
            "php" to CheckFormats.PHP, "json" to ParsedFormat.JSON,
            "po" to CheckFormats.PO,
        )
    }
}
