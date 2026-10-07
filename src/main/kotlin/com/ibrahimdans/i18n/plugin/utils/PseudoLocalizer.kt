package com.ibrahimdans.i18n.plugin.utils

/**
 * Turns a message into its pseudo-localized form: `Settings` → `[Šéţţîñĝš ···]`.
 *
 * Run in the application, a pseudo-locale shows at a glance what was never extracted (it stays in
 * plain text) and what overflows its box (the text is about 30 % longer, as German or French
 * often are). Brackets show where a message is cut.
 *
 * Only the text a user reads changes. A variable, a tag or a format specifier is kept as is, so
 * the message still interpolates: `{{name}}`, `{{- name}}`, `%{name}`, `{name}`, `%s` / `%1$s`,
 * `<1>…</1>`, `<br/>`, HTML entities. In an ICU block — `{count, plural, one {# item} other
 * {# items}}` — the argument, the keywords and the selectors are kept and only the text of each
 * branch is accented.
 *
 * Deterministic: the same message always gives the same result, so regenerating an unchanged file
 * changes nothing.
 */
internal object PseudoLocalizer {

    /** One `·` for every [PADDING_RATIO] letters of text, rounded up. */
    private const val PADDING_RATIO = 0.3

    private val ICU_TYPES = setOf("plural", "select", "selectordinal")

    /** Placeholders, tags, entities and format specifiers outside braces: kept verbatim. */
    private val PROTECTED = Regex(
        """%\{[^{}]*}|%(?:\d+\$)?[sdif]|</?[A-Za-z0-9_.-]+(?:\s[^<>]*?)?\s*/?>|&#?\w+;"""
    )

    private val ACCENTS: Map<Char, Char> = (
        "abcdefghijklmnopqrstuvwxyz".zip("áƀçđéƒĝĥîĵķļɱñöþǫŕšţûṽŵẋýž") +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ".zip("ÅƁÇĐÉƑĜĤÎĴĶĻṀÑÖÞǪŔŠŢÛṼŴẊÝŽ")
        ).toMap()

    /** [message] pseudo-localized; an empty or blank message is returned as is. */
    fun localize(message: String): String {
        if (message.isBlank()) return message
        val counter = LetterCounter()
        val body = message(message, counter)
        val padding = "·".repeat(maxOf(1, Math.ceil(counter.letters * PADDING_RATIO).toInt()))
        return "[$body $padding]"
    }

    private class LetterCounter {
        var letters = 0
    }

    /** [text] with its readable parts accented and every brace group handled by [braces]. */
    private fun message(text: String, counter: LetterCounter): String {
        val out = StringBuilder()
        var index = 0
        while (index < text.length) {
            if (text[index] == '{') {
                val end = matchingBrace(text, index)
                if (end < 0) {
                    out.append(text, index, text.length)
                    break
                }
                out.append(braces(text.substring(index, end + 1), counter))
                index = end + 1
                continue
            }
            val next = text.indexOf('{', index).let { if (it < 0) text.length else it }
            out.append(plain(text.substring(index, next), counter))
            index = next
        }
        return out.toString()
    }

    /**
     * A `{…}` group: a variable (`{name}`, `{{name}}`, `{amount, number}`) is kept; an ICU
     * `plural` / `select` / `selectordinal` keeps its argument and selectors, its branches
     * being messages of their own.
     */
    private fun braces(group: String, counter: LetterCounter): String {
        val inner = group.substring(1, group.length - 1)
        val parts = topLevelSplit(inner, limit = 3)
        if (parts.size < 3 || parts[1].trim() !in ICU_TYPES) return group
        val branches = StringBuilder()
        var index = 0
        val rest = parts[2]
        while (index < rest.length) {
            val open = rest.indexOf('{', index)
            if (open < 0) {
                branches.append(rest, index, rest.length)
                break
            }
            val close = matchingBrace(rest, open)
            if (close < 0) return group
            branches.append(rest, index, open + 1)
            branches.append(message(rest.substring(open + 1, close), counter))
            branches.append('}')
            index = close + 1
        }
        return "{${parts[0]},${parts[1]},$branches}"
    }

    /** [text] outside any brace: letters accented, protected tokens kept. */
    private fun plain(text: String, counter: LetterCounter): String {
        val out = StringBuilder()
        var index = 0
        for (match in PROTECTED.findAll(text)) {
            out.append(accent(text.substring(index, match.range.first), counter))
            out.append(match.value)
            index = match.range.last + 1
        }
        out.append(accent(text.substring(index), counter))
        return out.toString()
    }

    private fun accent(text: String, counter: LetterCounter): String =
        text.map { char ->
            if (char.isLetter()) counter.letters++
            ACCENTS[char] ?: char
        }.joinToString("")

    /** The index of the `}` closing the `{` at [open], or -1 when it is never closed. */
    private fun matchingBrace(text: String, open: Int): Int {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return -1
    }

    /** [text] split on its commas outside braces, into at most [limit] parts. */
    private fun topLevelSplit(text: String, limit: Int): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        for ((index, char) in text.withIndex()) {
            when (char) {
                '{' -> depth++
                '}' -> depth--
                ',' -> if (depth == 0 && parts.size < limit - 1) {
                    parts += text.substring(start, index)
                    start = index + 1
                }
            }
        }
        parts += text.substring(start)
        return parts
    }
}
