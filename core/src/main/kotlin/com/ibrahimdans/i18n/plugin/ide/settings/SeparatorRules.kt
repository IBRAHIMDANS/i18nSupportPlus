package com.ibrahimdans.i18n.plugin.ide.settings

/**
 * What a separator setting may hold, checked before the key pipeline reads it.
 *
 * The pipeline takes every separator for one character and nothing says so when it is not.
 * `NsKeyTokenizer` splits a literal with `StringTokenizer(literal, nsSeparator + keySeparator, true)`,
 * whose delimiter argument is a *set of characters*: `"::"` splits on each `:` and the one-character
 * tokens it yields never equal `"::"`, so they come back as plain literals and the key loses its
 * namespace. `KeyRangesCalculator` then places the annotations with `nsSeparatorOffset = 1` and
 * `keySeparatorOffset = 1`. The user saw keys cut in the wrong place and highlights shifted by a
 * character, with no message; the settings page and `i18n-check.json` had accepted the value.
 *
 * Supporting longer separators would mean rewriting the tokenizer and the range arithmetic for a
 * value no framework defaults to: the rule refuses them instead. Problems are reported as data,
 * the user-visible wording belongs to the caller.
 */
object SeparatorRules {

    /** The three separator settings, in the order the settings page shows them. */
    enum class Separator { NAMESPACE, KEY, PLURAL }

    /** What is wrong with a separator. */
    enum class Fault {
        /** Blank, for a separator the pipeline cannot do without. */
        EMPTY,

        /** Longer than one character: split character by character by the tokenizer. */
        SEVERAL_CHARACTERS,

        /** One of [REFUSED_CHARACTERS]. */
        REFUSED_CHARACTER,

        /** Equal to another separator, so that a key cannot tell one from the other. */
        SAME_AS_ANOTHER
    }

    /**
     * One problem with the value [value] of [separator]; [other] names the separator it clashes with
     * for [Fault.SAME_AS_ANOTHER], and is null otherwise.
     */
    data class Problem(val separator: Separator, val fault: Fault, val value: String, val other: Separator? = null)

    /**
     * The characters no separator may be: whitespace, the braces and dollar of a template
     * interpolation, the backtick that closes a template literal.
     */
    const val REFUSED_CHARACTERS = " {}$`"

    /**
     * Every problem the namespace separator [ns], the key separator [key] and the plural separator
     * [plural] have, in the order of [Separator].
     *
     * Each must be exactly one character. Only the namespace separator may be empty, which i18next
     * allows (`nsSeparator: false`) and the tokenizer reads as "no namespace"; an empty key or plural
     * separator leaves every composite or plural key unresolved. The namespace and key separators
     * must differ: the same character in both roles makes `a:b` either a namespace or a path.
     * A same-value clash is reported once, on the key separator.
     */
    fun check(ns: String, key: String, plural: String): List<Problem> = buildList {
        addAll(problemsOf(Separator.NAMESPACE, ns, emptyAllowed = true))
        addAll(problemsOf(Separator.KEY, key, emptyAllowed = false))
        if (key.isNotEmpty() && key == ns) add(Problem(Separator.KEY, Fault.SAME_AS_ANOTHER, key, other = Separator.NAMESPACE))
        addAll(problemsOf(Separator.PLURAL, plural, emptyAllowed = false))
    }

    private fun problemsOf(separator: Separator, value: String, emptyAllowed: Boolean): List<Problem> = when {
        value.isEmpty() -> if (emptyAllowed) emptyList() else listOf(Problem(separator, Fault.EMPTY, value))
        value.length > 1 -> listOf(Problem(separator, Fault.SEVERAL_CHARACTERS, value))
        value[0] in REFUSED_CHARACTERS -> listOf(Problem(separator, Fault.REFUSED_CHARACTER, value))
        else -> emptyList()
    }
}
