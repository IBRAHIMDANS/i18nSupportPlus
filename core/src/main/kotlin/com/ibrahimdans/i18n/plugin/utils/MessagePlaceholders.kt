package com.ibrahimdans.i18n.plugin.utils

/**
 * Message variables, in the four shapes the plugin recognises:
 * i18next's `{{count}}`, i18n-js' `%{count}`, ICU / react-intl's `{name}`, and printf's
 * `%s` / `%1$s`.
 */
object MessagePlaceholders {

    /**
     * The `{{…}}` and `%{…}` alternatives come before the single-brace one on purpose: on
     * `{{count}}` or `%{count}` it would match `{count}` and report a variable no translator
     * ever typed.
     */
    private val VARIABLE_REGEX = Regex("""\{\{[^{}]+}}|%\{[^{}]+}|\{[^{}]+}|%[0-9]*\$?[sd]""")

    /** Where each message variable sits inside [text]. */
    fun variableRanges(text: String): List<IntRange> =
        VARIABLE_REGEX.findAll(text).map { it.range }.toList()
}
