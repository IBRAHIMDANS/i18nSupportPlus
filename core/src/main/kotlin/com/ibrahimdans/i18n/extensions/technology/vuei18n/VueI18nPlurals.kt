package com.ibrahimdans.i18n.extensions.technology.vuei18n

import com.ibrahimdans.i18n.plugin.tree.PluralCategories

/**
 * vue-i18n's plurals: one value holds every form, separated by `|` — `no items | one item | {count} items`.
 *
 * vue-i18n picks a branch through the application's `pluralRules` for the locale, else through its
 * default rule, which knows English only (`singular | plural`, or `zero | one | many`). A Russian
 * value written with two branches therefore never says `2 предмета`: it needs a branch for each
 * form its language uses with an integer count — `one`, `few` and `many` — and the `pluralRules`
 * that picks them.
 *
 * Only forms **missing** are counted: a branch more is allowed, vue-i18n's documentation puts a
 * zero form first.
 */
object VueI18nPlurals {

    /**
     * The branches of [value]: none when it holds no `|`, so it is no plural. A `|` inside braces
     * is no separator — `{'|'}` is vue-i18n's literal pipe, `{a|b}` no message vue-i18n compiles.
     */
    fun branchesOf(value: String): List<String> {
        val branches = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in value) {
            when {
                c == '{' -> { depth++; current.append(c) }
                c == '}' -> { if (depth > 0) depth--; current.append(c) }
                c == '|' && depth == 0 -> { branches += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        if (branches.isEmpty()) return emptyList()
        return branches + current.toString()
    }

    /**
     * The forms [locale] needs a branch for, in CLDR order: one per plural form an integer count
     * reaches. `other` is left out for the languages that have a `many` — `ru`, `pl` read every
     * integer as `one`, `few` or `many`, `other` being for decimals — which asks one form less of
     * `ar` and `cy` than they use: a missed report rather than a false one.
     */
    fun formsOf(locale: String): List<String> {
        val categories = PluralCategories.of(locale)
        val forms = if (PluralCategories.MANY in categories) categories - PluralCategories.OTHER else categories
        return PluralCategories.ALL.filter { it in forms }
    }
}
