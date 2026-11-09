package com.ibrahimdans.i18n.plugin.key

/**
 * A `${…}` written inside a key segment: a part only known at runtime. A flat key keeps its
 * template whole, `` t(`role.${role}.label`) `` being the one segment `role.${role}.label`, which
 * names every entry the runtime value can complete it into.
 */
object Interpolation {

    private val PATTERN = Regex("""\$\{[^}]*}""")

    /** True when [segment] holds an interpolation. */
    fun isIn(segment: String): Boolean = PATTERN.containsMatchIn(segment)

    /** The names [segment] matches, each interpolation standing for at least one character. */
    fun matcher(segment: String): Regex =
        Regex(segment.split(PATTERN).joinToString(".+") { Regex.escape(it) })

    /** The text of [segment] before its first interpolation: what every name it matches starts with. */
    fun staticPrefix(segment: String): String = segment.substringBefore("\${")
}
