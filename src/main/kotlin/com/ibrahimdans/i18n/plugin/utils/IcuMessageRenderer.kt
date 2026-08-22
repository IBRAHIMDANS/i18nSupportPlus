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
 * Anything that is not a recognised argument block is returned verbatim, and any malformed input
 * falls back to the raw message rather than throwing.
 */
object IcuMessageRenderer {

    /** Blocks carrying branches, of which one representative is rendered. */
    private val BRANCHED_TYPES = setOf("plural", "select", "selectordinal")

    /** Blocks formatting a single value: no value at display time, so only the argument shows. */
    private val FORMATTED_TYPES = setOf("number", "date", "time")

    /** The characters an apostrophe may quote; anywhere else it is just an apostrophe. */
    private val QUOTABLE = setOf('{', '}', '#')

    /**
     * Renders [message] for display. Messages without a single `{` — the vast majority, i18next
     * projects included — are returned unchanged without any scanning.
     */
    fun render(message: String): String {
        if (message.indexOf('{') < 0) return message
        return renderRange(message, 0, message.length) ?: message
    }

    /**
     * Renders `text[from, to)`, or null when the braces are unbalanced.
     *
     * [hash] is the text an unquoted `#` stands for inside a plural branch; null everywhere else,
     * where `#` is plain text.
     */
    private fun renderRange(text: String, from: Int, to: Int, hash: String? = null): String? {
        val out = StringBuilder(to - from)
        var i = from
        while (i < to) {
            val char = text[i]
            if (char == '\'') {
                i = readQuoted(text, i, to, out)
                continue
            }
            if (char == '#' && hash != null) {
                out.append(hash)
                i++
                continue
            }
            if (char == '}') return null
            if (char != '{') {
                out.append(char)
                i++
                continue
            }
            val close = matchingBrace(text, i, to) ?: return null
            // Not a recognised argument block (plain interpolation…): keep it verbatim.
            out.append(renderBlock(text, i + 1, close) ?: text.substring(i, close + 1))
            i = close + 1
        }
        return out.toString()
    }

    /**
     * Reads the ICU quoting construct starting at [i], which holds a `'`, appending its decoded
     * text to [out] when one is given, and returns the index just past it.
     *
     * ICU's default (DOUBLE_OPTIONAL) rules: `''` is a literal apostrophe; a lone `'` opens quoted
     * literal text only when it precedes `{`, `}` or `#`, and that run ends at the next lone `'`.
     * Anywhere else an apostrophe is just an apostrophe — which is what keeps French translations
     * such as `L'article` untouched.
     */
    private fun readQuoted(text: String, i: Int, to: Int, out: StringBuilder?): Int {
        if (i + 1 < to && text[i + 1] == '\'') {
            out?.append('\'')
            return i + 2
        }
        if (i + 1 >= to || text[i + 1] !in QUOTABLE) {
            out?.append('\'')
            return i + 1
        }
        var j = i + 1
        while (j < to) {
            if (text[j] == '\'') {
                if (j + 1 < to && text[j + 1] == '\'') {
                    out?.append('\'')
                    j += 2
                    continue
                }
                return j + 1
            }
            out?.append(text[j])
            j++
        }
        // Unterminated quote: the rest was consumed as literal text, which is ICU's own behaviour.
        return j
    }

    /** Renders the inside of a `{…}` block, or null when it is not one this layer understands. */
    private fun renderBlock(text: String, from: Int, to: Int): String? {
        val firstComma = text.indexOf(',', from).takeIf { it in from until to } ?: return null
        val argument = text.substring(from, firstComma).trim()
        if (argument.isEmpty() || argument.any { it == '{' || it == '}' }) return null

        val secondComma = text.indexOf(',', firstComma + 1).takeIf { it in from until to }
        val type = text.substring(firstComma + 1, secondComma ?: to).trim().lowercase()

        // `{amount, number, currency}` carries no value here, so the format is noise: show `{amount}`.
        if (type in FORMATTED_TYPES) return "{$argument}"
        if (type !in BRANCHED_TYPES || secondComma == null) return null

        val branch = selectBranch(text, secondComma + 1, to) ?: return null
        // `#` stands for the count in plural forms only; in a `select` block it is plain text.
        val hash = if (type == "select") null else "{$argument}"
        return renderRange(branch, 0, branch.length, hash)
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
        var i = open
        while (i < to) {
            when (text[i]) {
                // A quoted brace must not be counted, or the whole message falls back.
                '\'' -> {
                    i = readQuoted(text, i, to, null)
                    continue
                }
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
            i++
        }
        return null
    }
}

/** Fluent alias of [IcuMessageRenderer.render], to keep the display call sites readable. */
fun String.renderIcu(): String = IcuMessageRenderer.render(this)
