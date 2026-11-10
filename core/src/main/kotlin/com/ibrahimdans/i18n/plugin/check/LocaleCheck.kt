package com.ibrahimdans.i18n.plugin.check

import com.ibrahimdans.i18n.plugin.tree.PluralCategories
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.utils.LocaleParent

/**
 * What one locale gets wrong compared to the reference, each list sorted:
 * [missing] the keys it lacks, [orphans] the keys the reference does not hold, [empty] the keys it
 * holds with a blank value.
 */
data class LocaleProblems(
    val locale: String,
    val missing: List<String>,
    val orphans: List<String>,
    val empty: List<String>,
) {
    val count: Int get() = missing.size + orphans.size + empty.size
}

/**
 * The comparison of every locale with the reference, on the key -> locale -> value map of a
 * project's translations, wherever they were read from: the `i18n-check` command line and the IDE
 * (the AI agents' `i18n_check`) give the same answer.
 *
 * A plural group ([PluralKey.groupForms]) is compared as a group: a locale must hold the forms its
 * own language needs ([PluralCategories]), not the reference's — `ja` writes `item_other` only,
 * `ru` adds `item_few` and `item_many`. Each form it lacks is a missing key, the one to add —
 * unless the locale holds the bare key `item`, i18next's fallback for any form missing. An
 * ordinal group (`place_ordinal_one`…) takes the language's ordinal categories
 * ([PluralCategories.ordinalOf]): `ru` writes `place_ordinal_other` alone, `en` four forms.
 *
 * A key a regional locale lacks is not missing when its language holds it ([LocaleParent]): an
 * `en-GB` file only spells out what differs from `en`. Its orphan and empty keys stay reported.
 */
object LocaleCheck {

    /**
     * The problems of every locale of [translations], sorted by locale, compared to [reference].
     * [pluralLocale] gives the locale whose plural forms a label needs: an alias's target (`gb` -> `en-GB`).
     * [parentLocale] gives the locale a label inherits the keys it lacks from, null for none.
     */
    fun run(
        translations: Map<String, Map<String, String>>,
        reference: String,
        pluralLocale: (String) -> String = { it },
        parentLocale: (String) -> String? = LocaleParent::of,
    ): List<LocaleProblems> {
        val locales = translations.values.flatMap { it.keys }.distinct().sorted()
        // A key that is both plain and a group's base (`count` beside `count_one`) stays a plain key.
        val plurals = PluralKey.groupForms(translations.keys).filter { (base, forms) -> forms != listOf(base) }
        val singles = translations.keys - plurals.values.flatten().toSet()

        fun has(key: String, locale: String) = translations[key]?.containsKey(locale) == true
        fun blank(key: String, locale: String) = translations[key]?.get(locale)?.isBlank() == true

        return locales.map { locale ->
            val parent = parentLocale(locale)
            fun held(key: String) = has(key, locale) || (parent != null && has(key, parent))
            val missing = mutableListOf<String>()
            val orphans = mutableListOf<String>()
            val empty = mutableListOf<String>()
            for (key in singles) {
                when {
                    !has(key, reference) -> if (has(key, locale)) orphans += key
                    !held(key) -> missing += key
                    blank(key, locale) -> empty += key
                }
            }
            for ((base, forms) in plurals) {
                if (forms.none { has(it, reference) }) {
                    orphans += forms.filter { has(it, locale) }
                    continue
                }
                // The bare key stands in for every form missing ([PluralKey.bareKeyCoversForms]).
                if (!held(base)) {
                    missing += PluralKey.categoriesOf(base, pluralLocale(locale)).map { "${base}_$it" }.filterNot(::held)
                }
                empty += forms.filter { blank(it, locale) }
            }
            LocaleProblems(locale, missing.sorted(), orphans.sorted(), empty.sorted())
        }
    }
}
