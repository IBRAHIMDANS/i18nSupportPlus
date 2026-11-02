package com.ibrahimdans.i18n.plugin.format

import com.ibrahimdans.i18n.plugin.utils.TextSpan

/**
 * A Java `.properties` file read without the PSI, as `java.util.Properties` reads it but keeping
 * the order of the keys and where each one is: a flat tree, one leaf per key, the dotted key whole
 * (`user.name`), which the key separator then reads as `user` › `name` like any other spelling.
 *
 * - Comment lines (`#`, `!`) and blank lines are skipped.
 * - A line ending with an odd number of backslashes continues on the next one, whose leading
 *   whitespace is dropped.
 * - The key ends at the first unescaped `=`, `:` or whitespace; the escapes (`\uXXXX`, `\t`, `\n`,
 *   `\=`, `\:`…) are decoded in the key and in the value.
 * - The first occurrence of a duplicated key wins, as the other trees answer.
 */
class PropertiesTranslationTree private constructor(node: ParsedNode, children: List<PropertiesTranslationTree>?) :
    ParsedNodeTree(node, children) {

    companion object {

        /** The tree of [text]; never null, a `.properties` file always reads, an empty one holding no key. */
        fun parse(text: String): PropertiesTranslationTree {
            val leaves = mutableListOf<PropertiesTranslationTree>()
            val seen = HashSet<String>()
            for (line in logicalLines(text)) {
                val entry = entryOf(line) ?: continue
                if (!seen.add(entry.key.orEmpty())) continue
                leaves += PropertiesTranslationTree(entry, null)
            }
            return PropertiesTranslationTree(ParsedNode(null, null, null, TextSpan(0, text.length)), leaves)
        }

        /** A logical line: its [text] with the continuations joined, [offsets] mapping each character back to the source. */
        private class LogicalLine(val text: String, val offsets: IntArray, val end: Int)

        private fun logicalLines(text: String): List<LogicalLine> {
            val result = mutableListOf<LogicalLine>()
            val builder = StringBuilder()
            val offsets = mutableListOf<Int>()
            var continuing = false
            var start = 0
            while (start <= text.length) {
                val newline = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                val lineEnd = if (newline > start && text[newline - 1] == '\r') newline - 1 else newline
                var from = start
                // A continuation line, like any line, starts after its leading whitespace.
                while (from < lineEnd && text[from].isPropertiesWhitespace()) from++
                val blankOrComment = from == lineEnd || (!continuing && (text[from] == '#' || text[from] == '!'))
                if (!(blankOrComment && !continuing)) {
                    val continues = trailingBackslashes(text, from, lineEnd) % 2 == 1
                    val to = if (continues) lineEnd - 1 else lineEnd
                    for (i in from until to) {
                        builder.append(text[i])
                        offsets += i
                    }
                    if (continues && newline < text.length) {
                        continuing = true
                    } else {
                        result += LogicalLine(builder.toString(), offsets.toIntArray(), lineEnd)
                        builder.clear()
                        offsets.clear()
                        continuing = false
                    }
                }
                if (newline >= text.length) break
                start = newline + 1
            }
            return result
        }

        private fun trailingBackslashes(text: String, from: Int, to: Int): Int {
            var count = 0
            var i = to - 1
            while (i >= from && text[i] == '\\') {
                count++
                i--
            }
            return count
        }

        private fun entryOf(line: LogicalLine): ParsedNode? {
            val text = line.text
            if (text.isEmpty()) return null
            var keyEnd = 0
            while (keyEnd < text.length) {
                val c = text[keyEnd]
                if (c == '\\') {
                    keyEnd += 2
                    continue
                }
                if (c == '=' || c == ':' || c.isPropertiesWhitespace()) break
                keyEnd++
            }
            keyEnd = keyEnd.coerceAtMost(text.length)
            var valueStart = keyEnd
            while (valueStart < text.length && text[valueStart].isPropertiesWhitespace()) valueStart++
            if (valueStart < text.length && (text[valueStart] == '=' || text[valueStart] == ':')) {
                valueStart++
                while (valueStart < text.length && text[valueStart].isPropertiesWhitespace()) valueStart++
            }
            // `=value`, a key left empty, is no key.
            if (keyEnd == 0) return null
            val key = unescape(text.substring(0, keyEnd))
            val value = unescape(text.substring(valueStart))
            val keySpan = TextSpan(line.offsets[0], line.offsets[keyEnd - 1] + 1)
            val valueSpan = if (valueStart < text.length) TextSpan(line.offsets[valueStart], line.end) else TextSpan(line.end, line.end)
            return ParsedNode(key, value, keySpan, valueSpan)
        }

        /** [raw] with the escapes of a `.properties` file decoded: `\uXXXX`, `\t`, `\n`, `\r`, `\f`, and `\x` as `x`. */
        fun unescape(raw: String): String {
            if ('\\' !in raw) return raw
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c != '\\' || i == raw.lastIndex) {
                    out.append(c)
                    i++
                    continue
                }
                val next = raw[i + 1]
                when (next) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    'f' -> out.append('\u000c')
                    'u' -> {
                        val hex = raw.substring(i + 2, (i + 6).coerceAtMost(raw.length))
                        val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                        if (code != null) {
                            out.append(code.toChar())
                            i += 6
                            continue
                        }
                        out.append('u')
                    }
                    else -> out.append(next)
                }
                i += 2
            }
            return out.toString()
        }

        private fun Char.isPropertiesWhitespace() = this == ' ' || this == '\t' || this == '\u000c'
    }
}
