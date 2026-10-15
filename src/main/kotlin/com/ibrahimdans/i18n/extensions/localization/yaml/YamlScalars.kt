package com.ibrahimdans.i18n.extensions.localization.yaml

/**
 * The YAML spelling of a translation value: the text written after `key: ` that reads back as
 * that very string.
 *
 * `YAMLElementGenerator.createYamlKeyValue` pastes its value as YAML source: `Note: soon` became a
 * nested mapping, `Save # now` a value `Save` and a comment, `yes` a boolean. A value is therefore
 * written plain only when a plain scalar reads back as the same string, single-quoted otherwise,
 * and double-quoted when it holds what only escapes can carry (line breaks, control characters).
 */
internal object YamlScalars {

    enum class Style { PLAIN, SINGLE, DOUBLE }

    /** Characters that change the meaning of a plain scalar when they start it. */
    private const val INDICATORS = "-?:,[]{}#&*!|>'\"%@`"

    /** Plain scalars YAML 1.1 or 1.2 resolve to another type than a string: booleans, null, numbers, dates. */
    private val IMPLICIT = Regex(
        """(?i)(y|n|yes|no|true|false|on|off|null|~)""" +
            """|[-+]?(\d[\d_]*)?\.?\d[\d_]*([eE][-+]?\d+)?""" +
            """|[-+]?0x[0-9a-fA-F_]+|[-+]?0o?[0-7_]+|[-+]?0b[01_]+""" +
            """|[-+]?\.(inf|Inf|INF)|\.(nan|NaN|NAN)""" +
            """|\d{4}-\d\d?-\d\d?([Tt ].*)?|\d+(:[0-5]?\d)+(\.\d*)?"""
    )

    /** [value] spelled in the lightest style that reads back as it. */
    fun spell(value: String): String = spell(value, Style.PLAIN)

    /**
     * [value] spelled in [preferred] when that style can carry it, else in the next one that can:
     * plain, then single quotes, then double quotes. Editing a value keeps the quotes it had.
     */
    fun spell(value: String, preferred: Style): String = when {
        needsEscapes(value) -> doubleQuoted(value)
        preferred == Style.DOUBLE -> doubleQuoted(value)
        preferred == Style.SINGLE || !isSafePlain(value) -> "'" + value.replace("'", "''") + "'"
        else -> value
    }

    /** The style of a scalar written as [text] in the file. */
    fun styleOf(text: String): Style = when (text.firstOrNull()) {
        '\'' -> Style.SINGLE
        '"' -> Style.DOUBLE
        else -> Style.PLAIN
    }

    private fun needsEscapes(value: String): Boolean = value.any { it < ' ' || it == '\u007f' }

    private fun isSafePlain(value: String): Boolean =
        value.isNotEmpty() &&
            value.first() !in INDICATORS &&
            value.first() != ' ' && value.last() != ' ' &&
            !value.endsWith(":") &&
            ": " !in value && " #" !in value &&
            !IMPLICIT.matches(value)

    private fun doubleQuoted(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\r' -> append("\\r")
                else -> if (c < ' ' || c == '\u007f') append("\\x%02x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
