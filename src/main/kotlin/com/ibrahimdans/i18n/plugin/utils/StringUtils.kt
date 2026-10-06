package com.ibrahimdans.i18n.plugin.utils

/**
 * Checks if string is quoted
 */
fun String.isQuoted(): Boolean =
    (this.length > 1) && listOf("\"", "'", "`").any {quote -> this.startsWith(quote) && this.endsWith(quote)}

/**
 * Unquotes a string
 */
fun String.unQuote(): String = if (this.isQuoted()) this.substring(1, this.length - 1) else this

/**
 * String ellipsis
 */
fun String.ellipsis(maxLen:Int): String =
    if (this.length > maxLen) {
        this.substring(0, maxLen) + "..."
    } else {
        this
    }

/** How many characters [displayValue] keeps before cutting a value off. */
internal const val DISPLAY_VALUE_MAX_LENGTH = 200

/**
 * Normalizes a raw translation value for single-line display — the Table View cells,
 * the completion's type text, Search Everywhere's results:
 * collapses all whitespace runs (including newlines) to one space, trims,
 * and truncates to [maxLength] with an ellipsis. Callers keep the raw value at hand
 * (a tooltip, the lookup strings) for whatever the line cuts off.
 */
internal fun displayValue(raw: String, maxLength: Int = DISPLAY_VALUE_MAX_LENGTH): String {
    val collapsed = raw.replace(Regex("\\s+"), " ").trim()
    return if (collapsed.length <= maxLength) collapsed else collapsed.take(maxLength) + "…"
}
