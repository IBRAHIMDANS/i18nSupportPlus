package com.ibrahimdans.i18n.plugin.utils

/**
 * A span of text between two offsets, [start] included and [end] excluded.
 *
 * The platform-free counterpart of IntelliJ's `TextRange`: the plugin converts at its edge.
 */
data class TextSpan(val start: Int, val end: Int) {

    /** True when the span covers no character. */
    val isEmpty: Boolean get() = start == end
}
