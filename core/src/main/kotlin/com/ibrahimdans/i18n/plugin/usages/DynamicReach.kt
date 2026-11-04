package com.ibrahimdans.i18n.plugin.usages

/**
 * Whether a key the code builds at runtime can produce a given translation key.
 *
 * The IDE's *Usage* column and `i18n-check` both answer it, from the static ends of one dynamic
 * key literal: its head before the first interpolation and its tail after the last one. The two
 * used to hold their own copy of the rule, and the copies drifted: the IDE stopped letting
 * `` t(`errors.${'$'}{code}`) `` reach `auth:errors.timeout`, the CLI still did, so the same key was
 * an orphan in the editor and *dynamic* in CI. One function, called by both, keeps the verdicts
 * equal.
 */
object DynamicReach {

    /**
     * Whether [key] — [bare] without its namespace — is one a literal of static [head] and [tail]
     * can produce: it starts with the one and ends with the other, written with or without its
     * namespace.
     *
     * A literal that is one segment and an interpolation, nothing more (`errors.` of
     * `` t(`errors.${'$'}{code}`) ``), says nothing about its namespace: it reaches the keys of the
     * default namespace only, the ones written without a namespace, rather than every `errors.*`
     * of every namespace. A tail narrows the shape enough to keep the plain rule
     * (`` menu.${'$'}{id}.label ``).
     */
    fun reaches(head: String, tail: String, key: String, bare: String, nsSeparator: String, keySeparator: String): Boolean {
        if (key != bare && tail.isEmpty() && isBareSegment(head, nsSeparator, keySeparator)) return false
        return (key.startsWith(head) || bare.startsWith(head)) && (key.endsWith(tail) || bare.endsWith(tail))
    }

    /** True when [head] is one key segment followed by the key separator: `errors.`. */
    private fun isBareSegment(head: String, nsSeparator: String, keySeparator: String): Boolean {
        if (keySeparator.isEmpty() || !head.endsWith(keySeparator)) return false
        val segment = head.dropLast(keySeparator.length)
        return segment.isNotEmpty() && !segment.contains(keySeparator) &&
            (nsSeparator.isEmpty() || !segment.contains(nsSeparator))
    }
}
