package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.ParsedFormat
import com.ibrahimdans.i18n.format.TsCatalogTree
import java.nio.file.Files
import java.nio.file.Path

/**
 * The formats `i18n-check` reads: those `core` builds in, and the TypeScript / JavaScript catalogues,
 * which need tree-sitter. Their id is the plugin's TS localization's, so its per-format settings apply.
 */
internal object CheckFormats {

    val TS = ParsedFormat("TS", "ts", listOf("ts", "js")) { text, _ -> TsCatalogTree.parse(text) }

    val ALL: List<ParsedFormat> = ParsedFormat.entries + TS

    /**
     * Why [file], of [format], gave no tree: null for a TS or JS file exporting no object literal — a
     * code file sitting among the translations, left out — and the reason to stop the check otherwise.
     */
    fun unreadable(format: ParsedFormat, file: Path, shownAs: String = file.toString()): String? {
        if (format !== TS) return "not a valid ${format.name} translation file: $shownAs"
        return when (val reading = TsCatalogTree.read(Files.readString(file))) {
            TsCatalogTree.Reading.NoCatalogue -> null
            is TsCatalogTree.Reading.Partial ->
                "cannot check $shownAs: its catalogue is not written as plain literals (${reading.construct} at line ${reading.line})"
            // parse() and read() agree: a catalogue would have given a tree.
            is TsCatalogTree.Reading.Catalogue -> null
        }
    }
}
