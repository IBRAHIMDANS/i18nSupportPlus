package com.ibrahimdans.i18n.plugin.format

import com.ibrahimdans.i18n.plugin.utils.TextSpan

/**
 * A gettext catalogue (`.po`, `.pot`) read without the PSI: a flat tree, one leaf per entry, its
 * `msgid` the key and its `msgstr` the value — the plugin's `PlainObjectTextTree` reads the same keys.
 *
 * - The header (`msgid ""`) is no key; comments, flags and obsolete entries (`#~`) are skipped.
 * - An entry with a `msgctxt` is another key than the same `msgid` without one, as gettext reads it:
 *   it is spelled `msgid (msgctxt: context)`.
 * - A plural entry (`msgid_plural`, `msgstr[0]`, `msgstr[1]`…) is one key; its value is empty when
 *   any of its forms is, so a form left to translate is reported as an empty value.
 */
class PoTranslationTree private constructor(node: ParsedNode, children: List<PoTranslationTree>?) : ParsedNodeTree(node, children) {

    companion object {

        /** The tree of [text]; null when a line is neither a comment, a keyword line nor a string continuing one. */
        fun parse(text: String): PoTranslationTree? {
            val entries = mutableListOf<PoTranslationTree>()
            var entry = Entry()
            var field: Field? = null
            var offset = 0
            for (rawLine in text.split('\n')) {
                val lineStart = offset
                offset += rawLine.length + 1
                val line = rawLine.trimEnd('\r').trim()
                val indent = rawLine.length - rawLine.trimStart().length
                when {
                    line.isEmpty() || line.startsWith("#") -> continue
                    line.startsWith("\"") -> {
                        val string = readString(line) ?: return null
                        field?.append(string) ?: return null
                    }
                    else -> {
                        val keyword = line.takeWhile { !it.isWhitespace() }
                        val string = readString(line.drop(keyword.length).trim()) ?: return null
                        // A msgctxt or msgid after a msgstr starts the next entry, with or without a blank line between.
                        if ((keyword == "msgctxt" || keyword == "msgid") && entry.hasValue()) {
                            entry.toLeaf()?.let(entries::add)
                            entry = Entry()
                        }
                        val start = lineStart + indent + keyword.length + 1
                        field = Field(string, TextSpan(start, lineStart + rawLine.trimEnd('\r').length))
                        when {
                            keyword == "msgctxt" -> entry.context = field
                            keyword == "msgid" -> entry.id = field
                            keyword == "msgid_plural" -> entry.plural = field
                            keyword == "msgstr" -> entry.values += field
                            PLURAL_FORM.matches(keyword) -> entry.values += field
                            else -> return null
                        }
                    }
                }
            }
            entry.toLeaf()?.let(entries::add)
            return PoTranslationTree(ParsedNode(null, null, null, TextSpan(0, text.length)), entries)
        }

        private val PLURAL_FORM = Regex("msgstr\\[\\d+]")

        /** The text of the C string [quoted], escapes decoded; null when it is not one. */
        private fun readString(quoted: String): String? {
            if (quoted.length < 2 || !quoted.startsWith('"') || !quoted.endsWith('"')) return null
            val result = StringBuilder()
            var i = 1
            while (i < quoted.length - 1) {
                val c = quoted[i]
                if (c == '\\' && i + 1 < quoted.length - 1) {
                    result.append(
                        when (val escaped = quoted[i + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            else -> escaped
                        },
                    )
                    i += 2
                } else {
                    result.append(c)
                    i++
                }
            }
            return result.toString()
        }
    }

    /** One keyword's string, its continuation lines appended; [span] covers its first line's string. */
    private class Field(var text: String, val span: TextSpan) {
        fun append(more: String) {
            text += more
        }
    }

    private class Entry {
        var context: Field? = null
        var id: Field? = null
        var plural: Field? = null
        val values = mutableListOf<Field>()

        fun hasValue(): Boolean = values.isNotEmpty()

        /** The entry as a leaf; null for the header, or for an entry without a msgid. */
        fun toLeaf(): PoTranslationTree? {
            val id = id ?: return null
            if (id.text.isEmpty() && context == null) return null
            val key = context?.let { "${id.text} (msgctxt: ${it.text})" } ?: id.text
            val value = if (values.any { it.text.isEmpty() }) "" else values.joinToString("\n") { it.text }
            return PoTranslationTree(ParsedNode(key, value, id.span, values.firstOrNull()?.span ?: id.span), null)
        }
    }
}
