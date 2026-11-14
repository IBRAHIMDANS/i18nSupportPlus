package com.ibrahimdans.i18n.plugin.tree

import kotlin.math.abs

/**
 * The keys of a translation file close to a key it lacks: `button.save` for a `buton.save` typed in
 * the code. A typo is a few edits in one or two segments of a key whose shape is right, so a key is
 * compared segment by segment with keys of as many segments, each segment allowed an edit count
 * that grows with its length:
 *  - up to 2 characters, none — `id` and `ok` are one edit apart and say nothing alike;
 *  - up to 5 characters, one;
 *  - beyond, two;
 *
 * and the whole key at most [MAX_TOTAL_EDITS]. An edit is an insertion, a deletion, a substitution
 * or two neighbours swapped (`sevae` for `save`).
 *
 * Linear in the size of the file: each key is first ruled out by its number of segments and their
 * lengths, and [keysOf] stops after [MAX_KEYS] keys.
 */
object SimilarKeys {

    /** At most this many keys are suggested. */
    const val LIMIT = 3

    /** Edits allowed over the whole key. */
    const val MAX_TOTAL_EDITS = 2

    /** Keys read from one file at most, whatever its size. */
    const val MAX_KEYS = 20_000

    /** The edits [segment], as typed, may be away from a key's segment. */
    fun allowedEdits(segment: String): Int = when {
        segment.length <= 2 -> 0
        segment.length <= 5 -> 1
        else -> 2
    }

    /**
     * The [limit] keys of [candidates] closest to [unknown], the closest first, then in alphabetical
     * order; [unknown] itself is never one of them.
     */
    fun closest(unknown: List<String>, candidates: Iterable<List<String>>, limit: Int = LIMIT): List<List<String>> {
        if (unknown.isEmpty()) return emptyList()
        return candidates.asSequence()
            .filter { it.size == unknown.size }
            .mapNotNull { candidate -> distance(unknown, candidate)?.let { candidate to it } }
            .distinctBy { it.first }
            .sortedWith(compareBy<Pair<List<String>, Int>> { it.second }.thenBy { it.first.joinToString("\u0000") })
            .take(limit)
            .map { it.first }
            .toList()
    }

    /**
     * The [limit] namespaces of [candidates] closest to [unknown], a namespace no file holds
     * (`comon` for `common`): one name compared as a key of one segment, with the same bounds.
     */
    fun closestNamespaces(unknown: String, candidates: Iterable<String>, limit: Int = LIMIT): List<String> =
        closest(listOf(unknown), candidates.map { listOf(it) }, limit).map { it.single() }

    /**
     * The edits from [unknown] to [candidate], two keys of as many segments, or null when they are
     * the same key or further apart than a typo.
     */
    fun distance(unknown: List<String>, candidate: List<String>): Int? {
        if (unknown.size != candidate.size) return null
        var total = 0
        for (i in unknown.indices) {
            val allowed = minOf(allowedEdits(unknown[i]), MAX_TOTAL_EDITS - total)
            total += editDistance(unknown[i], candidate[i], allowed) ?: return null
        }
        return total.takeIf { it > 0 }
    }

    /**
     * The optimal string alignment distance of [a] and [b] — insertions, deletions, substitutions
     * and swaps of two neighbours — or null when it exceeds [max].
     */
    fun editDistance(a: String, b: String, max: Int): Int? {
        if (a == b) return 0
        if (max <= 0 || abs(a.length - b.length) > max) return null
        var beforePrevious = IntArray(b.length + 1)
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var d = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d = minOf(d, beforePrevious[j - 2] + 1)
                current[j] = d
                if (d < rowMin) rowMin = d
            }
            // Every later row is at least this row's minimum: past [max], no alignment comes back.
            if (rowMin > max) return null
            val recycled = beforePrevious
            beforePrevious = previous
            previous = current
            current = recycled
        }
        return previous[b.length].takeIf { it <= max }
    }

    /**
     * The keys of [tree], a translation file's root, as their segments: its leaves, a plural form
     * (`item_one`) read as the key the code writes (`item`). [nameOf] names a node of a format whose
     * tree cannot list its entries. At most [maxKeys] keys, in the file's order.
     */
    fun <T> keysOf(tree: Tree<T>, nameOf: (Tree<T>) -> String, pluralSeparator: String, maxKeys: Int = MAX_KEYS): List<List<String>> {
        val keys = LinkedHashSet<List<String>>()
        val pending = ArrayDeque<Pair<List<String>, Tree<T>>>()
        pending.addLast(emptyList<String>() to tree)
        var visited = 0
        while (pending.isNotEmpty() && keys.size < maxKeys && visited < maxKeys * 2) {
            val (path, node) = pending.removeFirst()
            visited++
            if (node.isLeaf()) {
                if (path.isNotEmpty()) keys += path.dropLast(1) + PluralKey.stripSuffix(path.last(), pluralSeparator)
                continue
            }
            val children = node.entries() ?: node.findChildren("").map { nameOf(it) to it }
            children.forEach { (name, child) -> pending.addLast(path + name to child) }
        }
        return keys.toList()
    }

    /**
     * The key [source], as written in the code, once its composite key [compositeKey] — read under
     * the hook's [keyPrefix] — is replaced by [candidate]: the namespace written before it is kept,
     * the prefix the hook prepends is not written. Null when [source] does not end with the key it
     * reads, or when [candidate] lies outside the prefix: the call could not name it.
     */
    fun rewrite(
        source: String,
        compositeKey: List<String>,
        keyPrefix: List<String>,
        candidate: List<String>,
        keySeparator: String,
    ): String? {
        val prefix = if (keyPrefix.isEmpty()) "" else keyPrefix.joinToString(keySeparator) + keySeparator
        val read = compositeKey.joinToString(keySeparator)
        val written = if (prefix.isEmpty()) read else read.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) ?: return null
        if (written.isEmpty() || !source.endsWith(written)) return null
        val target = candidate.joinToString(keySeparator)
        val targetWritten = if (prefix.isEmpty()) target else target.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) ?: return null
        if (targetWritten.isEmpty()) return null
        return source.dropLast(written.length) + targetWritten
    }

    /**
     * The key [source], as written in the code, with its namespace [namespace] — written first,
     * before [nsSeparator] — replaced by [candidate], the key after it kept. Null when [source]
     * does not start with that namespace: the call names it elsewhere (a hook, an option).
     */
    fun rewriteNamespace(source: String, namespace: String, candidate: String, nsSeparator: String): String? {
        val written = namespace + nsSeparator
        if (namespace.isEmpty() || candidate.isEmpty() || !source.startsWith(written)) return null
        return candidate + nsSeparator + source.removePrefix(written)
    }
}
