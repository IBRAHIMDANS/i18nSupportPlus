package com.ibrahimdans.i18n.plugin.ide.settings.rules

/** A line of the framework file that [FrameworkYaml] cannot read. */
class FrameworkYamlException(val line: Int, message: String) : IllegalArgumentException("line $line: $message")

/**
 * Reads the YAML subset an i18n Ally custom framework is written in: top-level keys holding a
 * scalar (plain, single- or double-quoted), a block list (`- item`) or a flow list (`[a, b]`),
 * and comments.
 *
 * Not a YAML library on purpose. The YAML plugin is an optional dependency, so its PSI may be
 * missing; and SnakeYAML, which the platform bundles for its own use, is not part of the API a
 * plugin can count on across the IDE versions supported. The file is a handful of flat keys, which
 * this covers; anything richer — a nested mapping, a multi-line string — is rejected with its line
 * rather than misread.
 */
internal object FrameworkYaml {

    private val KEY = Regex("""^([A-Za-z_][\w-]*)\s*:(?:\s+(.*))?$""")

    /**
     * The top-level keys of [text], in order: a [String], a [Boolean] for a plain `true` / `false`,
     * or a list of strings.
     *
     * @throws FrameworkYamlException on a construct outside the subset.
     */
    fun parse(text: String): Map<String, Any> {
        val root = linkedMapOf<String, Any>()
        var list: MutableList<String>? = null
        text.lines().forEachIndexed { index, raw ->
            val line = index + 1
            val content = raw.trim()
            if (content.isEmpty() || content.startsWith("#") || content == "---") return@forEachIndexed
            if (content == "-" || content.startsWith("- ")) {
                val items = list ?: throw FrameworkYamlException(line, "list item outside a list")
                items += Scanner(content.substring(1).trimStart(), line).single().toString()
                return@forEachIndexed
            }
            if (raw.first().isWhitespace()) throw FrameworkYamlException(line, "nested mapping")
            val match = KEY.matchEntire(raw.trimEnd()) ?: throw FrameworkYamlException(line, "expected 'key: value'")
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            list = null
            root[key] = when {
                value.isEmpty() || value.startsWith("#") -> mutableListOf<String>().also { list = it }
                value.startsWith("|") || value.startsWith(">") -> throw FrameworkYamlException(line, "multi-line string")
                value.startsWith("[") -> Scanner(value, line).flowList()
                else -> Scanner(value, line).single()
            }
        }
        return root
    }

    /** Reads the value of one line of [text], declared at [line] for the errors. */
    private class Scanner(private val text: String, private val line: Int) {
        private var at = 0

        /** A single scalar, then nothing but a comment. */
        fun single(): Any {
            val value = scalar(flow = false)
            end()
            return value
        }

        /** `[a, 'b', "c"]`, then nothing but a comment. */
        fun flowList(): List<String> {
            at++
            val items = mutableListOf<String>()
            while (true) {
                blanks()
                if (peek() == ']' && items.isEmpty()) break
                items += scalar(flow = true).toString()
                blanks()
                when (peek()) {
                    ',' -> at++
                    ']' -> break
                    else -> fail("expected ',' or ']'")
                }
            }
            at++
            end()
            return items
        }

        private fun scalar(flow: Boolean): Any = when (peek()) {
            '"' -> doubleQuoted()
            '\'' -> singleQuoted()
            else -> plain(flow)
        }

        private fun plain(flow: Boolean): Any {
            val start = at
            while (at < text.length && !(flow && text[at] in ",]") && !(text[at] == '#' && at > start && text[at - 1].isWhitespace())) at++
            val value = text.substring(start, at).trim()
            return when (value) {
                "true" -> true
                "false" -> false
                else -> value
            }
        }

        private fun singleQuoted(): String {
            val value = StringBuilder()
            at++
            while (true) {
                if (at >= text.length) fail("unterminated quoted string")
                val char = text[at++]
                if (char != '\'') value.append(char)
                else if (peek() == '\'') value.append(text[at++])
                else return value.toString()
            }
        }

        private fun doubleQuoted(): String {
            val value = StringBuilder()
            at++
            while (true) {
                if (at >= text.length) fail("unterminated quoted string")
                when (val char = text[at++]) {
                    '"' -> return value.toString()
                    '\\' -> value.append(
                        when (text.getOrNull(at++)) {
                            '\\' -> '\\'
                            '"' -> '"'
                            '/' -> '/'
                            'n' -> '\n'
                            't' -> '\t'
                            else -> fail("unsupported escape")
                        }
                    )
                    else -> value.append(char)
                }
            }
        }

        private fun end() {
            blanks()
            if (at < text.length && text[at] != '#') fail("unexpected '${text.substring(at)}'")
        }

        private fun blanks() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun peek(): Char? = text.getOrNull(at)

        private fun fail(message: String): Nothing = throw FrameworkYamlException(line, message)
    }
}
