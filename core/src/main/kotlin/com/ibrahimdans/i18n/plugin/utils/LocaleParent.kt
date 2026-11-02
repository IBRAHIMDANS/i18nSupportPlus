package com.ibrahimdans.i18n.plugin.utils

/**
 * The language a regional locale falls back on: i18next, vue-i18n and next-intl read `en-GB` from
 * `en` for a key it does not hold, so a file of `en-GB` only spells out its differences.
 */
object LocaleParent {

    /** The language of [locale] — `en` for `en-GB` or `en_GB` — or null when it names no region. */
    fun of(locale: String): String? =
        locale.substringBefore('-').substringBefore('_').takeIf { it.isNotEmpty() && it != locale }
}
