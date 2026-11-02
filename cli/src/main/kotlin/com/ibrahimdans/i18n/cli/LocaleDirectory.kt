package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.plugin.format.ArbCatalogue
import com.ibrahimdans.i18n.plugin.format.ParsedNode
import com.ibrahimdans.i18n.plugin.format.PropertiesBundle
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.toolwindow.LeafReader
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationLeaves
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.LocaleNames
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * The translations of one directory holding a file or a folder per locale:
 * `<dir>/<locale>.json` or `<dir>/<locale>/<namespace>.json`, in JSON, JSON5, YAML, PO, as a Laravel PHP
 * array or as a TS / JS catalogue. A Flutter ARB catalogue (`app_fr.arb`) holds the locale its `@@locale`
 * states, or the one ending its name. A Java resource bundle (`messages_fr.properties`) holds the locale
 * ending its name, as [PropertiesBundle.placementsIn] reads it.
 *
 * Only names [LocaleNames] reads as a locale count: `README.md` or `scripts/` beside them are left
 * alone. The keys of a namespace file are spelled `namespace:key`, with the configured namespace
 * separator, as the code writes them.
 */
object LocaleDirectory {

    private val FORMATS = CheckFormats.ALL.flatMap { format -> format.extensions.map { it to format } }.toMap()

    /** Key -> locale -> value, what [TranslationCheck] compares, and its files; no key when [dir] holds no translation file. */
    fun read(dir: Path, config: Config): Scope {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        val files = mutableListOf<LocaleFile>()
        val aliases = config.localeAliasMap()
        fun isLocale(name: String) = LocaleNames.looksLikeLocale(name) || name in aliases
        val bundles = mutableListOf<Path>()
        for (entry in dir.listDirectoryEntries().sorted()) {
            when {
                entry.isDirectory() && isLocale(entry.fileName.toString()) ->
                    translationFiles(entry).forEach { file ->
                        collect(file, "${file.nameWithoutExtension}${config.nsSeparator}", entry.fileName.toString(), config, result, files)
                    }
                isTranslationFile(entry) && entry.extension.lowercase() == ArbCatalogue.EXTENSION -> collectArb(entry, config, result, files)
                isTranslationFile(entry) && entry.extension.lowercase() == PropertiesBundle.EXTENSION -> bundles.add(entry)
                isTranslationFile(entry) && isLocale(entry.nameWithoutExtension) ->
                    collect(entry, "", entry.nameWithoutExtension, config, result, files)
            }
        }
        collectBundles(bundles, config, result, files)
        return Scope(null, result, files)
    }

    private fun collectBundles(bundles: List<Path>, config: Config, result: MutableMap<String, MutableMap<String, String>>, files: MutableList<LocaleFile>) {
        val placements = PropertiesBundle.placementsIn(bundles.map { it.nameWithoutExtension }, ReferenceLocale.wanted(null, config))
        for (file in bundles) {
            val placement = placements[file.nameWithoutExtension] ?: continue
            val nsPrefix = placement.namespace?.let { it + config.nsSeparator }.orEmpty()
            TranslationLeaves.collect(config, CheckFormats.PROPERTIES.parse(Files.readString(file))!!, nsPrefix, placement.locale, ParsedLeaves, result)
            files += LocaleFile(placement.locale, nsPrefix, file.toAbsolutePath().normalize())
        }
    }

    private fun collectArb(file: Path, config: Config, result: MutableMap<String, MutableMap<String, String>>, files: MutableList<LocaleFile>) {
        val tree = CheckFormats.ARB.parse(Files.readString(file)) ?: throw CannotCheck(CheckFormats.unreadable(CheckFormats.ARB, file)!!)
        val locale = ArbCatalogue.localeOf(file.nameWithoutExtension, ArbCatalogue.declaredLocale(tree)) ?: return
        TranslationLeaves.collect(config, tree, "", locale, ParsedLeaves, result)
        files += LocaleFile(locale, "", file.toAbsolutePath().normalize())
    }

    private fun translationFiles(dir: Path): List<Path> = dir.listDirectoryEntries().filter(::isTranslationFile).sorted()

    private fun isTranslationFile(path: Path): Boolean = path.isRegularFile() && path.extension.lowercase() in FORMATS

    private fun collect(
        file: Path,
        nsPrefix: String,
        locale: String,
        config: Config,
        result: MutableMap<String, MutableMap<String, String>>,
        files: MutableList<LocaleFile>,
    ) {
        val extension = file.extension.lowercase()
        val format = FORMATS.getValue(extension)
        val tree = format.parse(Files.readString(file), extension)
            ?: CheckFormats.unreadable(format, file)?.let { throw CannotCheck(it) }
            ?: return
        TranslationLeaves.collect(config, tree, nsPrefix, locale, ParsedLeaves, result)
        files += LocaleFile(locale, nsPrefix, file.toAbsolutePath().normalize())
    }
}

/** A parsed node names itself: its key, and the text of a leaf. */
internal object ParsedLeaves : LeafReader<ParsedNode> {
    override fun nameOf(node: Tree<ParsedNode>): String? = node.value().key
    override fun valueOf(node: Tree<ParsedNode>): String = node.value().value.orEmpty()
}
