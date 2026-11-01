package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.utils.MessagePlaceholders

/**
 * The translations a module already holds, looked up by their source text: a text the project has
 * translated under another key is given that translation instead of going to an engine. Nothing is
 * sent and nothing paid for, and a term is written the way the project already writes it.
 *
 * A match is exact once whitespace is collapsed and the first letter's case ignored: `Save` finds
 * `"save"` → `"enregistrer"` and is given `Enregistrer`, the first letter's case following the text
 * looked up. A translation is only offered when it carries the same placeholders as the text
 * ([MessagePlaceholders]): an existing value that lost `{{name}}` is no example to copy.
 *
 * Built from the translations of one module: the memory of another module is never read.
 */
class TranslationMemory private constructor(private val entries: Map<String, List<Entry>>) {

    /**
     * What the memory offers for a text: [value], held by [keys] of the keys translating that text.
     * [unanimous] when every one of them holds it; otherwise [value] is the most frequent, the first
     * in key order on a tie, and the others say the project translates the text in several ways.
     */
    data class Match(val value: String, val unanimous: Boolean, val keys: Int)

    /** The translation the memory holds for [text], or null when no other key translates it. */
    fun lookup(text: String): Match? {
        val normalized = normalize(text) ?: return null
        val wanted = placeholdersOf(text)
        val values = entries[normalized].orEmpty()
            .filter { placeholdersOf(it.target) == wanted }
            .map { it.translationFor(text) }
        if (values.isEmpty()) return null
        val counts = values.groupingBy { it }.eachCount()
        val best = counts.entries.maxBy { it.value }
        return Match(best.key, counts.size == 1, best.value)
    }

    /** A pair of the project: [source] in the source locale, translated as [target]. */
    private class Entry(val source: String, val target: String) {

        /**
         * [target] for [text]: as it is, unless [text] and [source] start with letters of a different
         * case — `save` looked up against `Save` — in which case its first letter takes [text]'s case.
         */
        fun translationFor(text: String): String {
            val asked = text.trimStart().firstOrNull()?.takeIf(Char::isLetter) ?: return target
            val held = source.trimStart().firstOrNull()?.takeIf(Char::isLetter) ?: return target
            if (asked.isUpperCase() == held.isUpperCase()) return target
            return if (asked.isUpperCase()) target.replaceFirstChar(Char::uppercaseChar) else target.replaceFirstChar(Char::lowercaseChar)
        }
    }

    companion object {

        private val WHITESPACE = Regex("""\s+""")

        /**
         * The memory of [known] (`key -> locale -> value`) from [source] to [target]: every key holding
         * a non-blank value in both. The keys of [excluded] are left out — a key whose value is being
         * replaced is no example of how the project says it.
         */
        fun of(known: Map<String, Map<String, String>>, source: String, target: String, excluded: Set<String> = emptySet()): TranslationMemory {
            val entries = linkedMapOf<String, MutableList<Entry>>()
            known.entries.sortedBy { it.key }.forEach { (key, values) ->
                if (key in excluded) return@forEach
                val from = values[source]?.takeIf { it.isNotBlank() } ?: return@forEach
                val to = values[target]?.takeIf { it.isNotBlank() } ?: return@forEach
                val normalized = normalize(from) ?: return@forEach
                entries.getOrPut(normalized) { mutableListOf() } += Entry(from, to)
            }
            return TranslationMemory(entries)
        }

        /** [text] as the memory compares it: whitespace collapsed, its first letter in lower case; null when blank. */
        internal fun normalize(text: String): String? =
            text.trim().replace(WHITESPACE, " ").takeIf { it.isNotEmpty() }?.replaceFirstChar(Char::lowercaseChar)

        /** The placeholders of [text], sorted: two texts carrying the same ones in any order compare equal. */
        private fun placeholdersOf(text: String): List<String> =
            MessagePlaceholders.variableRanges(text).map { text.substring(it) }.sorted()
    }
}
