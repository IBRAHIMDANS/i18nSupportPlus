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
 *
 * Ordinal forms (`place_ordinal_few`) take other categories than the cardinal ones: [ordinalOf].
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
     * The ordinal categories of every language that has more than `other`, copied from CLDR 49
     * (`cldr-json` 49.0.0, `supplemental/ordinals.json`). Every other language — most of them,
     * `de`, `ru`, `ja` or `pt` among them — only has `other`.
     */
    private val ORDINAL_BY_LANGUAGE: Map<String, Set<String>> = buildMap {
        listOf("af", "be", "tk", "uk").forEach { put(it, setOf(FEW, OTHER)) }
        listOf("it", "kk", "lij", "lld", "sc", "scn", "vec").forEach { put(it, setOf(MANY, OTHER)) }
        listOf("bal", "es", "fil", "fr", "ga", "hu", "hy", "lo", "mo", "ms", "ne", "ro", "sv", "tl", "vi")
            .forEach { put(it, ONE_OTHER) }
        listOf("ka", "kw", "sq").forEach { put(it, setOf(ONE, MANY, OTHER)) }
        put("az", ONE_FEW_MANY_OTHER)
        listOf("bg", "ca", "en", "gd", "kok", "mr").forEach { put(it, setOf(ONE, TWO, FEW, OTHER)) }
        put("mk", setOf(ONE, TWO, MANY, OTHER))
        put("blo", setOf(ZERO, ONE, FEW, OTHER))
        listOf("as", "bn", "gu", "hi", "or").forEach { put(it, setOf(ONE, TWO, FEW, MANY, OTHER)) }
        put("cy", setOf(ZERO, ONE, TWO, FEW, MANY, OTHER))
    }

    /**
     * The categories [locale]'s language needs — `pt-BR` and `zh_Hant` read as `pt` and `zh` —
     * with the large-number `many` when [largeNumberForms] is set.
     */
    fun of(locale: String, largeNumberForms: Boolean = false): Set<String> {
        val language = languageOf(locale)
        val categories = BY_LANGUAGE[language] ?: ONE_OTHER
        return if (largeNumberForms && language in LARGE_NUMBER_MANY) categories + MANY else categories
    }

    /**
     * The categories [locale]'s language needs for an ordinal count, as i18next picks them through
     * `Intl.PluralRules` with `type: 'ordinal'` (`t('place', { count, ordinal: true })`): `en`
     * writes `place_ordinal_one/two/few/other`, `ru` only `place_ordinal_other`.
     *
     * An ordinal is always an integer, so every CLDR category counts. A language missing from the
     * table only has `other`, as most do: asking it for `one` would report a correct file.
     */
    fun ordinalOf(locale: String): Set<String> = ORDINAL_BY_LANGUAGE[languageOf(locale)] ?: OTHER_ONLY

    private fun languageOf(locale: String): String = locale.trim().lowercase().split('-', '_').first()
}
