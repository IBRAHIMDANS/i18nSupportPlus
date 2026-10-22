package com.ibrahimdans.i18n.plugin.tree

/**
 * The plural categories a language needs for an integer count, as i18next picks them through
 * `Intl.PluralRules` — `ru` writes `item_one`, `item_few`, `item_many`, `item_other`, `ja` only
 * `item_other`.
 *
 * A short static table taken from the CLDR cardinal rules (`plurals.json`), not a plural engine:
 * the platform does not ship ICU4J. Only the categories an **integer** count reaches are listed —
 * `cs` and `sk` have a `many` for decimals, which `t('key', { count })` with an integer never
 * selects, so it is left out rather than asked of every Czech plural.
 *
 * The `many` that French, Spanish, Italian, Portuguese and Catalan use for very large round
 * numbers (`1 000 000 de fichiers`) is real but almost never translated: it is only included when
 * [of] is asked for the large-number forms.
 *
 * A language missing from the table falls back to `one` / `other`, the English pair: asking for
 * less than the language needs is a missed report, asking for more would be a false one.
 */
object PluralCategories {

    const val ZERO = "zero"
    const val ONE = "one"
    const val TWO = "two"
    const val FEW = "few"
    const val MANY = "many"
    const val OTHER = "other"

    /** Every category, in CLDR order. */
    val ALL = listOf(ZERO, ONE, TWO, FEW, MANY, OTHER)

    private val OTHER_ONLY = setOf(OTHER)
    private val ONE_OTHER = setOf(ONE, OTHER)
    private val ONE_FEW_OTHER = setOf(ONE, FEW, OTHER)
    private val ONE_FEW_MANY_OTHER = setOf(ONE, FEW, MANY, OTHER)

    private val BY_LANGUAGE: Map<String, Set<String>> = buildMap {
        listOf("ja", "zh", "ko", "vi", "th", "id", "ms", "km", "lo", "my").forEach { put(it, OTHER_ONLY) }
        listOf("en", "de", "nl", "sv", "da", "nb", "no", "nn", "fi", "et", "el", "hu", "tr", "bg", "hi")
            .forEach { put(it, ONE_OTHER) }
        listOf("fr", "es", "it", "pt", "ca").forEach { put(it, ONE_OTHER) }
        listOf("cs", "sk", "ro", "hr", "sr", "bs").forEach { put(it, ONE_FEW_OTHER) }
        listOf("ru", "uk", "pl", "be").forEach { put(it, ONE_FEW_MANY_OTHER) }
        put("sl", setOf(ONE, TWO, FEW, OTHER))
        put("he", setOf(ONE, TWO, OTHER))
        put("ar", setOf(ZERO, ONE, TWO, FEW, MANY, OTHER))
        put("cy", setOf(ZERO, ONE, TWO, FEW, MANY, OTHER))
    }

    /** The languages whose `many` only names very large round numbers. */
    private val LARGE_NUMBER_MANY = setOf("fr", "es", "it", "pt", "ca")

    /**
     * The categories [locale]'s language needs — `pt-BR` and `zh_Hant` read as `pt` and `zh` —
     * with the large-number `many` when [largeNumberForms] is set.
     */
    fun of(locale: String, largeNumberForms: Boolean = false): Set<String> {
        val language = languageOf(locale)
        val categories = BY_LANGUAGE[language] ?: ONE_OTHER
        return if (largeNumberForms && language in LARGE_NUMBER_MANY) categories + MANY else categories
    }

    private fun languageOf(locale: String): String = locale.trim().lowercase().split('-', '_').first()
}
