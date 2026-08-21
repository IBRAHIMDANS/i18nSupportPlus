package com.ibrahimdans.i18n.plugin.utils

/**
 * Minimal ICU MessageFormat renderer, used by the display layer only (folding, hints, inlays).
 *
 * react-intl / FormatJS pluralize inside the value rather than through sub-keys, so a translation
 * often reads `{count, plural, one {# article} other {# articles}}`. Displayed as-is it is less
 * readable than the key it replaces, which defeats folding entirely.
 *
 * This is deliberately **not** an ICU engine: no argument value is available at display time, so a
 * representative branch is rendered instead — `other` when present, the first branch otherwise —
 * and `#` is replaced by the argument as a placeholder (`{count}`), consistent with the way the
 * plugin already leaves `{{name}}` / `{name}` interpolations untouched.
 *
 * Anything that is not a `plural` / `select` / `selectordinal` block is returned verbatim, and any
 * malformed input falls back to the raw message rather than throwing.
 */
object IcuMessageRenderer {

    private val ARGUMENT_TYPES = setOf("plural", "select", "selectordinal")

    /**
     * Renders [message] for display. Messages without a single `{` — the vast majority, i18next
     * projects included — are returned unchanged without any scanning.
     */
    fun render(message: String): String {
        if (message.indexOf('{') < 0) return message
        return renderRange(message, 0, message.length) ?: message
    }

    /** Renders `text[from, to)`, or null when the braces are unbalanced. */
    private fun renderRange(text: String, from: Int, to: Int): String? {
        val out = StringBuilder(to - from)
        var i = from
        while (i < to) {
            val char = text[i]
            if (char == '}') return null
            if (char != '{') {
                out.append(char)
                i++
                continue
            }
            val close = matchingBrace(text, i, to) ?: return null
            // Not an ICU argument block (plain interpolation, unsupported type…): keep it verbatim.
            out.append(renderBlock(text, i + 1, close) ?: text.substring(i, close + 1))
            i = close + 1
        }
        return out.toString()
    }

    /** Renders the inside of a `{…}` block, or null when it is not a plural/select/selectordinal one. */
    private fun renderBlock(text: String, from: Int, to: Int): String? {
        val firstComma = text.indexOf(',', from).takeIf { it in from until to } ?: return null
        val argument = text.substring(from, firstComma).trim()
        if (argument.isEmpty() || argument.any { it == '{' || it == '}' }) return null

        val secondComma = text.indexOf(',', firstComma + 1).takeIf { it in from until to } ?: return null
        val type = text.substring(firstComma + 1, secondComma).trim().lowercase()
        if (type !in ARGUMENT_TYPES) return null

        val branch = selectBranch(text, secondComma + 1, to) ?: return null
        val rendered = renderRange(branch, 0, branch.length) ?: return null
        // `#` stands for the count in plural forms only; in a `select` block it is plain text.
        return if (type == "select") rendered else rendered.replace("#", "{$argument}")
    }

    /** Returns the body of the `other` branch, falling back to the first branch declared. */
    private fun selectBranch(text: String, from: Int, to: Int): String? {
        var firstBranch: String? = null
        var i = from
        while (i < to) {
            if (text[i].isWhitespace()) {
                i++
                continue
            }
            val selectorStart = i
            while (i < to && !text[i].isWhitespace() && text[i] != '{') i++
            val selector = text.substring(selectorStart, i)
            while (i < to && text[i].isWhitespace()) i++
            if (i >= to || text[i] != '{') {
                // Token carrying no body, such as the `offset:1` of a plural block — skip it.
                continue
            }
            val close = matchingBrace(text, i, to) ?: return null
            val body = text.substring(i + 1, close)
            if (selector.equals("other", ignoreCase = true)) return body
            if (firstBranch == null) firstBranch = body
            i = close + 1
        }
        return firstBranch
    }

    /** Index of the `}` closing the `{` at [open], or null when it is never closed. */
    private fun matchingBrace(text: String, open: Int, to: Int): Int? {
        var depth = 0
        for (i in open until to) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return null
    }
}

/** Fluent alias of [IcuMessageRenderer.render], to keep the display call sites readable. */
fun String.renderIcu(): String = IcuMessageRenderer.render(this)
