package com.ibrahimdans.i18n.plugin.tree

/**
 * The flat plural forms i18next stores a key under, and how to get back from one of them to
 * the key the source code actually writes.
 *
 * `t('cart.item', { count })` never names a form: i18next appends the suffix at runtime and
 * looks up `cart.item_one` or `cart.item_other`. Anything reasoning about a key *read from a
 * translation file* — composing it for a reference, counting its usages — therefore has to
 * strip that suffix first, or it searches the sources for a string that by construction is
 * never written there.
 *
 * The two shapes recognised are the ones [CompositeKeyResolver.tryToResolvePlural] resolves,
 * so a key this strips is a key the resolver can find again:
 *  - modern CLDR categories (i18next v4+): `key_one`, `key_other`, …
 *  - legacy numeric forms (i18next v3): `key-1`, `key-2`, `key-5`, the separator configurable.
 */
object PluralKey {

    /** The CLDR categories i18next appends, in the flat `key_one` form. */
    private val CLDR_SUFFIXES = listOf("_zero", "_one", "_two", "_few", "_many", "_other")

    /** The counts the legacy numeric form uses. */
    private val NUMERIC_SUFFIXES = listOf("1", "2", "5")

    /**
     * [key] without its plural suffix, or [key] itself when it carries none.
     *
     * A key merely *ending* like a plural form (`step_one`) is stripped too — the two are
     * indistinguishable without reading the sibling keys, and the callers all prefer the
     * over-wide answer: it groups a key with a namesake, where the narrow one would report a
     * translated plural as unused.
     */
    fun stripSuffix(key: String, pluralSeparator: String): String = when {
        CLDR_SUFFIXES.any { key.endsWith(it) } -> key.substringBeforeLast("_")
        NUMERIC_SUFFIXES.any { key.endsWith(pluralSeparator + it) } -> key.substringBeforeLast(pluralSeparator)
        else -> key
    }

    /**
     * [keys] grouped the way a per-locale comparison must see them: each CLDR plural group as one
     * entry keyed by its base (`cart.item` for `cart.item_one` and `cart.item_other`), every other
     * key alone under itself.
     *
     * Plural categories depend on the language — `ru` has `one/few/many/other`, `en` `one/other`,
     * `ja` `other` only — so comparing the forms themselves reports `cart.item_few` missing in
     * English and `cart.item_one` missing in Japanese. The group is what a locale has or lacks.
     *
     * A lone form is only a group when it is `_other`, the one category every language has: a
     * single `step_one` with no sibling is far more likely a key named so than a plural.
     */
    fun groupForms(keys: Collection<String>): Map<String, List<String>> {
        val byBase = keys.groupBy { key -> CLDR_SUFFIXES.firstOrNull { key.endsWith(it) }?.let { key.dropLast(it.length) } }
        val result = linkedMapOf<String, List<String>>()
        byBase[null]?.forEach { result[it] = listOf(it) }
        byBase.forEach { (base, forms) ->
            if (base == null) return@forEach
            val distinct = forms.distinct()
            if (distinct.size >= 2 || distinct.single().endsWith(OTHER_SUFFIX)) result[base] = distinct
            else distinct.forEach { result[it] = listOf(it) }
        }
        return result
    }

    /** The form to create in a locale lacking a whole plural group: every language has `other`. */
    fun defaultForm(base: String): String = base + OTHER_SUFFIX

    private const val OTHER_SUFFIX = "_other"
}
