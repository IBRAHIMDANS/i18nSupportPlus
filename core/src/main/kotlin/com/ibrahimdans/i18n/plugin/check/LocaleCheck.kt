package com.ibrahimdans.i18n.plugin.check

import com.ibrahimdans.i18n.plugin.tree.PluralCategories
import com.ibrahimdans.i18n.plugin.tree.PluralKey

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
 * `ru` adds `item_few` and `item_many`. Each form it lacks is a missing key, the one to add.
 */
object LocaleCheck {

    /**
     * The problems of every locale of [translations], sorted by locale, compared to [reference].
     * [pluralLocale] gives the locale whose plural forms a label needs: an alias's target (`gb` -> `en-GB`).
     */
    fun run(translations: Map<String, Map<String, String>>, reference: String, pluralLocale: (String) -> String = { it }): List<LocaleProblems> {
        val locales = translations.values.flatMap { it.keys }.distinct().sorted()
        // A key that is both plain and a group's base (`count` beside `count_one`) stays a plain key.
        val plurals = PluralKey.groupForms(translations.keys).filter { (base, forms) -> forms != listOf(base) }
        val singles = translations.keys - plurals.values.flatten().toSet()

        fun has(key: String, locale: String) = translations[key]?.containsKey(locale) == true
        fun blank(key: String, locale: String) = translations[key]?.get(locale)?.isBlank() == true

        return locales.map { locale ->
            val missing = mutableListOf<String>()
            val orphans = mutableListOf<String>()
            val empty = mutableListOf<String>()
            for (key in singles) {
                when {
                    !has(key, reference) -> if (has(key, locale)) orphans += key
                    !has(key, locale) -> missing += key
                    blank(key, locale) -> empty += key
                }
            }
            for ((base, forms) in plurals) {
                if (forms.none { has(it, reference) }) {
                    orphans += forms.filter { has(it, locale) }
                    continue
                }
                missing += PluralCategories.of(pluralLocale(locale)).map { "${base}_$it" }.filterNot { has(it, locale) }
                empty += forms.filter { blank(it, locale) }
            }
            LocaleProblems(locale, missing.sorted(), orphans.sorted(), empty.sorted())
        }
    }
}
