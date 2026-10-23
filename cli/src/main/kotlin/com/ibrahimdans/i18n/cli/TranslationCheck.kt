package com.ibrahimdans.i18n.cli

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
 * The problems of every locale, sorted by locale, compared to [reference], in [module] — null for
 * the whole project — and, when the code was checked, the [unused] keys of the reference.
 */
data class CheckResult(
    val reference: String,
    val locales: List<LocaleProblems>,
    val module: String? = null,
    val unused: List<String>? = null,
) {
    val problems: Int get() = locales.sumOf { it.count } + unused.orEmpty().size
}

/** What a run found: a [CheckResult] per module, and when the code was checked, its [unknown] keys. */
data class ProjectResult(val modules: List<CheckResult>, val unknown: List<UnknownKey>? = null) {
    val problems: Int get() = modules.sumOf { it.problems } + unknown.orEmpty().size
}

/**
 * The check itself, on the key -> locale -> value map of a project's translations: wherever they
 * were read from, a directory ([LocaleDirectory]) or a whole project ([ProjectSources]).
 *
 * A plural group ([PluralKey.groupForms]) is compared as a group: a locale must hold the forms its
 * own language needs ([PluralCategories]), not the reference's — `ja` writes `item_other` only,
 * `ru` adds `item_few` and `item_many`. Each form it lacks is a missing key, the one to add.
 */
object TranslationCheck {

    fun run(translations: Map<String, Map<String, String>>, reference: String): CheckResult {
        val locales = translations.values.flatMap { it.keys }.distinct().sorted()
        // A key that is both plain and a group's base (`count` beside `count_one`) stays a plain key.
        val plurals = PluralKey.groupForms(translations.keys).filter { (base, forms) -> forms != listOf(base) }
        val singles = translations.keys - plurals.values.flatten().toSet()

        fun has(key: String, locale: String) = translations[key]?.containsKey(locale) == true
        fun blank(key: String, locale: String) = translations[key]?.get(locale)?.isBlank() == true

        return CheckResult(reference, locales.map { locale ->
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
                missing += PluralCategories.of(locale).map { "${base}_$it" }.filterNot { has(it, locale) }
                empty += forms.filter { blank(it, locale) }
            }
            LocaleProblems(locale, missing.sorted(), orphans.sorted(), empty.sorted())
        })
    }
}
