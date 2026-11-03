package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.NioWorkspace
import com.ibrahimdans.i18n.ParsedFormat
import com.ibrahimdans.i18n.TranslationSource
import com.ibrahimdans.i18n.TranslationTechnology
import com.ibrahimdans.i18n.Workspace
import com.ibrahimdans.i18n.extensions.technology.angulari18n.AngularI18nConfig
import com.ibrahimdans.i18n.plugin.format.ParsedNode
import com.ibrahimdans.i18n.plugin.format.SymfonyXliffTree
import com.ibrahimdans.i18n.plugin.format.XliffKeying
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Angular's own i18n (`@angular/localize`) as sources of a project check, as the plugin's
 * `AngularI18nTechnology` declares them: the XLIFF files the `i18n` section of `angular.json` names.
 * The source-locale file `ng extract-i18n` writes (`messages.xlf`) holds every id with its source text;
 * each locale's file (`messages.fr.xlf`) holds the ids it translates, a unit with no `<target>` being a
 * translation the locale lacks. Ids are keys as written, `home.title` whole.
 *
 * A file `angular.json` names but that does not exist yet is skipped: `ng extract-i18n` may not have run.
 */
internal class AngularSources : TranslationTechnology<ParsedNode, ParsedFormat> {

    override fun translationFunctionNames(): List<String> = emptyList()

    override fun initialize(workspace: Workspace) {}

    override fun cfgNamespaces(): List<String> = emptyList()

    override fun findSourcesByConfiguration(workspace: Workspace): List<TranslationSource<ParsedNode, ParsedFormat>> {
        val nio = workspace as? NioWorkspace ?: return emptyList()
        val root = Path.of(nio.basePath)
        val config = root.resolve(ANGULAR_JSON).takeIf { Files.isRegularFile(it) } ?: return emptyList()
        val projects = AngularI18nConfig.parse(Files.readString(config))
        val files = projects.flatMap { project ->
            val reference = project.referenceFiles.firstOrNull { Files.isRegularFile(root.resolve(it)) }
            listOfNotNull(reference?.let { Triple(it, project.sourceLocale, XliffKeying.ANGULAR_SOURCE) }) +
                project.translations.map { (locale, path) -> Triple(path, locale, XliffKeying.ANGULAR_TARGET) }
        }
        return files.distinctBy { it.first }.mapNotNull { (relative, locale, keying) ->
            val path = root.resolve(relative).normalize()
            if (!Files.isRegularFile(path)) return@mapNotNull null
            val tree = try {
                SymfonyXliffTree.parse(Files.readString(path), keying)
            } catch (e: IOException) {
                null
            }
            TranslationSource(
                tree, path.fileName.toString(), path.parent?.fileName?.toString().orEmpty(), relative,
                CheckFormats.ANGULAR_XLIFF, locale = locale,
            )
        }
    }

    private companion object {
        const val ANGULAR_JSON = "angular.json"
    }
}
