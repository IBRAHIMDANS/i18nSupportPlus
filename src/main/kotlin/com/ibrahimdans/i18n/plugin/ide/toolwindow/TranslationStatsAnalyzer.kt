package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.intellij.openapi.project.Project

/**
 * Statistics for a single locale.
 * [total] is the union of all keys across all locales.
 * [translated] is the number of non-blank values for this locale.
 * [missing] and [missingKeys] are the keys this locale does not carry at all; [empty] and
 * [emptyKeys] the ones it carries with a blank value. The two used to be folded into one
 * count while the tree told them apart (`✗` against `!`): `3 missing` here next to two `✗`
 * and one `!` there did not add up for a reader.
 */
data class LocaleStats(
    val locale: String,
    val total: Int,
    val translated: Int,
    val missing: Int,
    val percent: Double,
    val missingKeys: List<String> = emptyList(),
    val empty: Int = 0,
    val emptyKeys: List<String> = emptyList(),
) {
    /** Keys that are not translated, whichever way: absent or blank. */
    val untranslated: Int get() = missing + empty
}

/**
 * Coverage of one row of the statistics table: the keys of one namespace group, or of the
 * whole set when [group] is null, across every locale of the project.
 *
 * [byLocale] carries one [LocaleStats] per locale of the report — a locale holding none of
 * this group's keys is present at 0%, so a namespace never translated in a language reads as
 * such rather than vanishing from its row.
 */
data class NamespaceStats(
    val group: NamespaceFilter?,
    val total: Int,
    val byLocale: List<LocaleStats>,
) {
    /** The stats of [locale], or null when the report does not know that locale. */
    fun of(locale: String): LocaleStats? = byLocale.firstOrNull { it.locale == locale }
}

/**
 * What the statistics tab lays out: the locales as columns, and one row per namespace group
 * under a total row.
 *
 * [rows] holds the total first, then the namespaces — except when the keys all belong to a
 * single group, where the total would repeat that group's row and is left out. Coverage
 * used to be reported per locale alone, which said `fr 96%` and nothing about *where* the
 * missing 4% sat: with five namespaces, finding the one dragging a language down meant
 * opening the list of missing keys and reading its prefixes.
 */
data class CoverageReport(
    val locales: List<String>,
    val total: NamespaceStats,
    val namespaces: List<NamespaceStats>,
    /** The values the report was computed from, key -> locale -> value: what a missing key says in another locale. */
    val translations: Map<String, Map<String, String>> = emptyMap(),
) {
    val rows: List<NamespaceStats> get() = if (namespaces.size > 1) listOf(total) + namespaces else namespaces
}

/**
 * Analyzes translation coverage per locale.
 * Uses [TranslationDataLoader] to get the flat key -> (locale -> value) map,
 * then computes per-locale statistics.
 */
object TranslationStatsAnalyzer {

    /**
     * Analyzes coverage for each locale found in the project (or in a specific module).
     * Returns a list of [LocaleStats] sorted by locale name.
     */
    fun analyze(project: Project, moduleConfig: ModuleConfig? = null): List<LocaleStats> =
        analyze(TranslationDataLoader.loadAllTranslations(project, moduleConfig))

    /** The coverage report of the project (or of a specific module), see [CoverageReport]. */
    fun report(project: Project, moduleConfig: ModuleConfig? = null): CoverageReport =
        report(TranslationDataLoader.loadAllTranslations(project, moduleConfig))

    /**
     * Coverage of [allTranslations] (key -> locale -> value), for the locales found in it.
     *
     * A plural group counts once ([PluralKey.groupForms]) and is translated in a locale holding any
     * non-blank form of it: the categories differ per language, so counting forms reported
     * `item_few` missing in English and `item_one` missing in Japanese. A missing group is listed
     * under a form that exists elsewhere, so the popup can still navigate to it.
     */
    internal fun analyze(allTranslations: Map<String, Map<String, String>>): List<LocaleStats> =
        analyze(allTranslations, localesOf(allTranslations))

    /**
     * Coverage of [allTranslations] for exactly [locales], in that order — including a locale
     * none of these keys is translated in, which then reads as 0%.
     */
    internal fun analyze(allTranslations: Map<String, Map<String, String>>, locales: List<String>): List<LocaleStats> {
        val groups = PluralKey.groupForms(allTranslations.keys)
        val totalKeys = groups.size
        if (totalKeys == 0) return emptyList()

        return locales.map { locale ->
            // A group is translated by any non-blank form, empty when it only has blank ones,
            // missing when the locale carries none of its forms.
            val untranslated = groups.values.filter { forms -> forms.none { !allTranslations[it]?.get(locale).isNullOrBlank() } }
            val (emptyGroups, missingGroups) = untranslated.partition { forms -> forms.any { allTranslations[it]?.get(locale) != null } }
            val missingKeys = missingGroups.map { forms -> forms.sorted().first() }.sorted()
            val emptyKeys = emptyGroups.map { forms -> forms.sorted().first() }.sorted()
            val translated = totalKeys - missingKeys.size - emptyKeys.size
            val percent = translated.toDouble() / totalKeys * 100.0
            LocaleStats(
                locale = locale,
                total = totalKeys,
                translated = translated,
                missing = missingKeys.size,
                percent = percent,
                missingKeys = missingKeys,
                empty = emptyKeys.size,
                emptyKeys = emptyKeys,
            )
        }
    }

    /**
     * The report of [allTranslations]: the total row, then one row per namespace group — the
     * default group (keys spelled without a prefix) first, then the named ones sorted.
     */
    internal fun report(allTranslations: Map<String, Map<String, String>>): CoverageReport {
        val locales = localesOf(allTranslations)
        val total = NamespaceStats(null, PluralKey.groupForms(allTranslations.keys).size, analyze(allTranslations, locales))
        val namespaces = allTranslations.entries
            .groupBy({ KeySpelling.namespaceOf(it.key) }, { it.key to it.value })
            .map { (namespace, entries) ->
                val keys = entries.toMap()
                val group = if (namespace == null) NamespaceFilter.Default else NamespaceFilter.Named(namespace)
                NamespaceStats(group, PluralKey.groupForms(keys.keys).size, analyze(keys, locales))
            }
            .sortedWith(compareBy<NamespaceStats> { it.group !is NamespaceFilter.Default }.thenBy { it.group?.label })
        return CoverageReport(locales, total, namespaces, allTranslations)
    }

    private fun localesOf(allTranslations: Map<String, Map<String, String>>): List<String> =
        allTranslations.values.flatMap { it.keys }.distinct().sorted()
}
