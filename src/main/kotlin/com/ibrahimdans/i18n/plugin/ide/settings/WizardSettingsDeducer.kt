package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import java.io.File

/**
 * Turns what the setup wizard scanned into the settings it should store.
 *
 * The wizard used to apply a single one of them — the translations root — while holding
 * everything needed to infer several others, and its framework checkboxes fed nothing but a
 * sentence in the summary: a user could tick "react-intl", read that it was detected, click
 * *Apply*, and still have an entirely unresolved project because `flatKeys` was never set.
 *
 * UI-free like [FrameworkDetector], [TranslationFileScanner] and [TranslationRootDetector],
 * for the reason that produced all of them: the dialog cannot be instantiated headlessly, and
 * a test rewriting the rule would not be testing the shipped one.
 */
object WizardSettingsDeducer {

    /** A null field means "nothing could be inferred", never "set it back to the default". */
    data class Deduced(
        val defaultNs: String? = null,
        val gettext: Boolean? = null,
        val flatKeys: Boolean? = null,
        val preferredLocalization: String? = null
    ) {
        fun isEmpty(): Boolean =
            defaultNs == null && gettext == null && flatKeys == null && preferredLocalization == null
    }

    private val GETTEXT_EXTENSIONS = setOf("po", "pot")

    /** Namespaces conventionally used as the default one, preferred when several tie. */
    private val PREFERRED_NAMESPACES = listOf("translation", "common")

    /** Extension of a translation file to the id of the localization that reads it. */
    private val LOCALIZATION_BY_EXTENSION = mapOf(
        "json" to "json",
        "json5" to "json",
        "yaml" to "yaml",
        "yml" to "yaml"
    )

    /**
     * Settings inferred from the [relativeFilePaths] found and the [frameworks] ticked.
     *
     * `flatKeys` follows react-intl because that is what the setting was added for: FormatJS
     * stores flat ids (`"app.header.title"` is one property, not three levels), which nested
     * resolution can never match.
     */
    fun deduce(relativeFilePaths: List<String>, frameworks: Set<String>): Deduced {
        if (relativeFilePaths.isEmpty()) return Deduced()
        return Deduced(
            defaultNs = deduceDefaultNamespace(relativeFilePaths),
            gettext = true.takeIf { relativeFilePaths.any { path -> path.extension() in GETTEXT_EXTENSIONS } },
            flatKeys = true.takeIf { "react-intl" in frameworks },
            preferredLocalization = deducePreferredLocalization(relativeFilePaths)
        )
    }

    /**
     * The namespace files are named after, when they are named after one at all.
     *
     * A "one file per locale" project (`locales/fr.json`) names its files after locales, not
     * namespaces: nothing is inferred there, and the default stays what it was — since #159
     * such keys resolve through the whole-scan fallback anyway.
     */
    private fun deduceDefaultNamespace(relativeFilePaths: List<String>): String? {
        val counts = relativeFilePaths
            .map { it.stem() }
            .filterNot { LocalizationSourceService.looksLikeLocale(it) }
            .groupingBy { it }
            .eachCount()
        if (counts.isEmpty()) return null
        val top = counts.values.max()
        val contenders = counts.filterValues { it == top }.keys
        // Ties are broken on convention first, then alphabetically, so the result never
        // depends on the order the file system happened to return.
        return PREFERRED_NAMESPACES.firstOrNull { it in contenders } ?: contenders.min()
    }

    /**
     * Written only when the current value is still the one [Settings] auto-picked — the first
     * registered localization — since that field is never left empty to compare against.
     * Returns null for a GetText project: no localization reads `.po`, `Config.gettext` does.
     */
    private fun deducePreferredLocalization(relativeFilePaths: List<String>): String? {
        val counts = relativeFilePaths
            .mapNotNull { LOCALIZATION_BY_EXTENSION[it.extension()] }
            .groupingBy { it }
            .eachCount()
        if (counts.isEmpty()) return null
        val top = counts.values.max()
        return counts.filterValues { it == top }.keys.min()
    }

    /** True when [current] still holds what nobody chose: the default, or the auto-picked id. */
    fun isUntouchedPreferredLocalization(current: String): Boolean =
        current.isEmpty() || current == Extensions.LOCALIZATION.extensionList.firstOrNull()?.config()?.id()

    private fun String.stem(): String = File(this).name.substringBeforeLast('.')

    private fun String.extension(): String = substringAfterLast('.', "").lowercase()
}
