package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.plugin.format.JsonTranslationTree
import com.ibrahimdans.i18n.plugin.format.ParsedNode
import com.ibrahimdans.i18n.plugin.format.YamlTranslationTree
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.toolwindow.LeafReader
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationLeaves
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.LocaleNames
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * The translations of one directory holding a file or a folder per locale:
 * `<dir>/<locale>.json` or `<dir>/<locale>/<namespace>.json`, in JSON or YAML.
 *
 * Only names [LocaleNames] reads as a locale count: `README.md` or `scripts/` beside them are left
 * alone. The keys of a namespace file are spelled `namespace:key`, with the configured namespace
 * separator, as the code writes them.
 */
object LocaleDirectory {

    private val EXTENSIONS = setOf("json", "yml", "yaml")

    /** Key -> locale -> value, what [TranslationCheck] compares; empty when [dir] holds no translation file. */
    fun read(dir: Path, config: Config): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        for (entry in dir.listDirectoryEntries().sorted()) {
            when {
                entry.isDirectory() && LocaleNames.looksLikeLocale(entry.fileName.toString()) ->
                    translationFiles(entry).forEach { file ->
                        collect(file, "${file.nameWithoutExtension}${config.nsSeparator}", entry.fileName.toString(), config, result)
                    }
                isTranslationFile(entry) && LocaleNames.looksLikeLocale(entry.nameWithoutExtension) ->
                    collect(entry, "", entry.nameWithoutExtension, config, result)
            }
        }
        return result
    }

    private fun translationFiles(dir: Path): List<Path> = dir.listDirectoryEntries().filter(::isTranslationFile).sorted()

    private fun isTranslationFile(path: Path): Boolean = path.isRegularFile() && path.extension.lowercase() in EXTENSIONS

    private fun collect(file: Path, nsPrefix: String, locale: String, config: Config, result: MutableMap<String, MutableMap<String, String>>) {
        val text = Files.readString(file)
        val json = file.extension.lowercase() == "json"
        val tree = (if (json) JsonTranslationTree.parse(text) else YamlTranslationTree.parse(text))
            ?: throw CannotCheck("not a valid ${if (json) "JSON" else "YAML"} translation file: $file")
        TranslationLeaves.collect(config, tree, nsPrefix, locale, ParsedLeaves, result)
    }

    /** A parsed node names itself: its key, and the text of a leaf. */
    private object ParsedLeaves : LeafReader<ParsedNode> {
        override fun nameOf(node: Tree<ParsedNode>): String? = node.value().key
        override fun valueOf(node: Tree<ParsedNode>): String = node.value().value.orEmpty()
    }
}
