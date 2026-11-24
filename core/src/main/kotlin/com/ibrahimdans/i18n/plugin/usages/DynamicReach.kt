package com.ibrahimdans.i18n.plugin.usages

import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.key.FullKey

/**
 * Which translation keys a key the code builds at runtime can produce.
 *
 * The IDE's *Usage* column and `i18n-check` both answer it, from the static ends of one dynamic
 * key literal: its head before the first interpolation and its tail after the last one. The two
 * used to hold their own copy of the rule, and the copies drifted: the IDE stopped letting
 * `` t(`errors.${'$'}{code}`) `` reach `auth:errors.timeout`, the CLI still did, so the same key was
 * an orphan in the editor and *dynamic* in CI. One reading of the literal and one rule, called by
 * both, keep the verdicts equal.
 */
object DynamicReach {

    /** What makes a key literal dynamic, in every language the plugin reads. */
    private const val INTERPOLATION = "\${"

    /**
     * The static ends of one dynamic key literal: [head] before its first interpolation, [tail]
     * after its last one. A key it can produce starts with the one and ends with the other.
     */
    data class Shape(val head: String, val tail: String) {

        /**
         * Whether [key] — [bare] without its namespace — is one this literal can produce: it
         * starts with the head and ends with the tail, written with or without its namespace.
         *
         * A literal that is one segment and an interpolation, nothing more (`errors.` of
         * `` t(`errors.${'$'}{code}`) ``), says nothing about its namespace: it reaches the keys of
         * the default namespace only, the ones written without a namespace, rather than every
         * `errors.*` of every namespace. A tail narrows the shape enough to keep the plain rule
         * (`` menu.${'$'}{id}.label ``).
         */
        fun reaches(key: String, bare: String, nsSeparator: String, keySeparator: String): Boolean {
            if (key != bare && tail.isEmpty() && isBareSegment(head, nsSeparator, keySeparator)) return false
            return (key.startsWith(head) || bare.startsWith(head)) && (key.endsWith(tail) || bare.endsWith(tail))
        }
    }

    /**
     * The shape of the key literal [text], or `null` when nothing in it is computed or nothing in
     * it is static — `` t(`${'$'}{name}`) `` says nothing about the keys it reaches.
     */
    fun shapeOf(text: String): Shape? {
        if (!text.contains(INTERPOLATION)) return null
        val lastEnd = text.indexOf('}', text.lastIndexOf(INTERPOLATION))
        val tail = if (lastEnd < 0) "" else text.substring(lastEnd + 1)
        return Shape(text.substringBefore(INTERPOLATION), tail).takeIf { it.head.isNotEmpty() || it.tail.isNotEmpty() }
    }

    /**
     * The ways the dynamic literal [text], read as [key], spells the keys it may produce: its own under the hook's
     * key prefix and, when it writes no namespace, one more per namespace it is looked up in —
     * `` t(`stages.${'$'}{s}`) `` under `useTranslation('crm')` also reads `crm:stages.${'$'}{s}`, which reaches the
     * keys a bare head leaves out. Empty when nothing in it is static.
     */
    fun spellingsOf(text: String, key: FullKey, nsSeparator: String, keySeparator: String): List<String> {
        val own = key.keyPrefix.joinToString("") { it.text + keySeparator } + text
        if (shapeOf(own) == null) return emptyList()
        if (key.ns != null) return listOf(own)
        // The tool window and `i18n-check` spell a key with `:`, the inspection with the configured separator.
        val separators = setOf(KeySpelling.NAMESPACE_SEPARATOR, nsSeparator).filter { it.isNotEmpty() }
        return listOf(own) + key.lookupNamespaces().flatMap { ns -> separators.map { ns + it + own } }
    }

    /** The subset of [keys] one of [shapes] reaches ([Shape.reaches]). */
    fun reached(keys: Collection<String>, shapes: Collection<Shape>, nsSeparator: String, keySeparator: String): Set<String> {
        if (shapes.isEmpty()) return emptySet()
        return keys.filterTo(mutableSetOf()) { key ->
            val bare = key.substringAfter(nsSeparator, key)
            shapes.any { it.reaches(key, bare, nsSeparator, keySeparator) }
        }
    }

    /** True when [head] is one key segment followed by the key separator: `errors.`. */
    private fun isBareSegment(head: String, nsSeparator: String, keySeparator: String): Boolean {
        if (keySeparator.isEmpty() || !head.endsWith(keySeparator)) return false
        val segment = head.dropLast(keySeparator.length)
        return segment.isNotEmpty() && !segment.contains(keySeparator) &&
            (nsSeparator.isEmpty() || !segment.contains(nsSeparator))
    }
}
