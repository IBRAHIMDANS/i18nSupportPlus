package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.ParsedFormat
import com.ibrahimdans.i18n.format.TsCatalogTree
import com.ibrahimdans.i18n.plugin.format.ArbCatalogue
import com.ibrahimdans.i18n.plugin.format.JsonTranslationTree
import com.ibrahimdans.i18n.plugin.format.PhpArrayTranslationTree
import com.ibrahimdans.i18n.plugin.format.PoTranslationTree
import com.ibrahimdans.i18n.plugin.format.PropertiesBundle
import com.ibrahimdans.i18n.plugin.format.PropertiesTranslationTree
import com.ibrahimdans.i18n.plugin.format.SymfonyXliffTree
import com.ibrahimdans.i18n.plugin.format.XliffKeying
import java.nio.file.Files
import java.nio.file.Path

/**
 * The formats `i18n-check` reads: those `core` builds in, the gettext catalogues, Laravel's PHP
 * arrays, Flutter's ARB catalogues, Java's resource bundles (`.properties`), Symfony's and Angular's XLIFF catalogues, and the TypeScript / JavaScript catalogues, which need
 * tree-sitter. Their ids are the plugin's localizations', so its per-format settings apply: an ARB
 * catalogue is a JSON file there.
 */
internal object CheckFormats {

    val TS = ParsedFormat("TS", "ts", listOf("ts", "js")) { text, _ -> TsCatalogTree.parse(text) }

    val PO = ParsedFormat("PO", "plainObject", listOf("po", "pot")) { text, _ -> PoTranslationTree.parse(text) }

    val PHP = ParsedFormat("PHP", "php", listOf("php")) { text, _ -> PhpArrayTranslationTree.parse(text) }

    /** Not scanned by name: [ArbSources] declares the catalogues, with the locale each one holds. */
    val ARB = ParsedFormat("ARB", "json", listOf(ArbCatalogue.EXTENSION), scannedByName = false) { text, _ ->
        JsonTranslationTree.parse(text, arb = true)
    }

    /** Not scanned by name: [PropertiesSources] declares the bundle files, with the locale each one holds. */
    val PROPERTIES = ParsedFormat("Properties", "properties", listOf(PropertiesBundle.EXTENSION), scannedByName = false) { text, _ ->
        PropertiesTranslationTree.parse(text)
    }

    /** Not scanned by name: [SymfonySources] declares the catalogues of a Symfony project, keyed as Symfony keys them. */
    val XLIFF = ParsedFormat("XLIFF", "xliff", listOf("xlf", "xliff"), scannedByName = false) { text, _ -> SymfonyXliffTree.parse(text) }

    /**
     * Not scanned by name: [AngularSources] declares the files `angular.json` names, each read by its role
     * (source-locale file or locale's file) — this parser reads a locale's file.
     */
    val ANGULAR_XLIFF = ParsedFormat("XLIFF", "xliff", listOf("xlf", "xliff"), scannedByName = false) { text, _ ->
        SymfonyXliffTree.parse(text, XliffKeying.ANGULAR_TARGET)
    }

    val ALL: List<ParsedFormat> = ParsedFormat.entries + listOf(TS, PO, PHP, ARB, PROPERTIES, XLIFF, ANGULAR_XLIFF)

    /**
     * Formats whose code `--code` does not read, Dart and Java: their keys are never reported unused. Angular's
     * ids are read from the code's `i18n="…@@id"` markers and `$localize` templates ([CodeUsages]).
     */
    val CODE_UNREAD: Set<ParsedFormat> = setOf(ARB, PROPERTIES)

    /**
     * Why [file], of [format], gave no tree: null for a TS, JS or PHP file holding code rather than a
     * catalogue — a helper sitting among the translations, left out — and the reason to stop the check otherwise.
     */
    fun unreadable(format: ParsedFormat, file: Path, shownAs: String = file.toString()): String? = when (format) {
        TS -> when (val reading = TsCatalogTree.read(Files.readString(file))) {
            TsCatalogTree.Reading.NoCatalogue -> null
            is TsCatalogTree.Reading.Partial -> notLiteral(shownAs, reading.construct, reading.line)
            // parse() and read() agree: a catalogue would have given a tree.
            is TsCatalogTree.Reading.Catalogue -> null
        }
        PHP -> when (val reading = PhpArrayTranslationTree.read(Files.readString(file))) {
            PhpArrayTranslationTree.Reading.NoArray -> null
            is PhpArrayTranslationTree.Reading.Partial -> notLiteral(shownAs, reading.construct, reading.line)
            is PhpArrayTranslationTree.Reading.Catalogue -> null
        }
        else -> "not a valid ${format.name} translation file: $shownAs"
    }

    private fun notLiteral(shownAs: String, construct: String, line: Int) =
        "cannot check $shownAs: its catalogue is not written as plain literals ($construct at line $line)"
}
