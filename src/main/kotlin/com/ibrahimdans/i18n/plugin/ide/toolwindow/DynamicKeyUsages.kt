package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext

/**
 * Which keys are reached by a key the code builds at runtime.
 *
 * `t(`deposit-box:status.${'$'}{kind}`)` names none of the `status.*` keys, so the text scan
 * behind the *Usage* column finds nothing for any of them and calls all three orphans. They
 * are in use, and deleting them breaks that call site.
 *
 * The rule is deliberately one of *shape*, not of resolution: what a template literal will
 * hold is unknown until it runs, so every key starting with its static head and ending with the
 * static text after its last interpolation is treated as reachable. `t(`${'$'}{ns}:status.ok`)`
 * has no head, and its tail `:status.ok` alone protects `status.ok` in every namespace. That
 * over-approximates — a genuinely dead `status.obsolete` is spared as well — which is the side
 * to err on when the alternative is offering a live key for deletion. A literal with neither a
 * head nor a tail (`t(`${'$'}{name}`)`) protects nothing: that is what the keep list is for.
 *
 * Asking the PSI instead was tried and does not work: the reference a template literal carries
 * resolves onto the *key literal* of the JSON property, while `ReferencesSearch` on that
 * property compares against the property itself and finds nothing. That is what the cleanup's
 * own guard did, so it never protected anything.
 */
object DynamicKeyUsages {

    /** What makes a key literal dynamic, in every language the plugin reads. */
    private const val INTERPOLATION = "\${"

    /**
     * The static ends of one dynamic key literal: [head] before its first interpolation, [tail]
     * after its last one. A key it can produce starts with the one and ends with the other.
     */
    internal data class Shape(val head: String, val tail: String) {
        fun reaches(key: String, bare: String): Boolean =
            (key.startsWith(head) || bare.startsWith(head)) && (key.endsWith(tail) || bare.endsWith(tail))
    }

    /**
     * The subset of [keys] some dynamic key literal can reach.
     *
     * One search per distinct prefix or suffix rather than per key: the three `status.*` keys
     * share the single word `deposit-box:status`, and the scan runs over every key a project holds.
     */
    fun reachedKeys(
        keys: List<String>,
        scope: GlobalSearchScope,
        searchHelper: PsiSearchHelper,
        nsSeparator: String,
        keySeparator: String,
    ): Set<String> {
        if (keys.isEmpty()) return emptySet()
        val shapes = searchWords(keys, nsSeparator, keySeparator)
            .flatMapTo(mutableSetOf()) { dynamicLiterals(it, scope, searchHelper) }
            .mapNotNullTo(mutableSetOf(), ::shapeOf)
        if (shapes.isEmpty()) return emptySet()

        return keys.filterTo(mutableSetOf()) { key ->
            val bare = key.substringAfter(nsSeparator, key)
            shapes.any { it.reaches(key, bare) }
        }
    }

    /**
     * Whether one key is reachable, for a caller holding one key at a time.
     *
     * [literals] is the caller's own cache of the dynamic literals each word leads to, kept
     * across the keys of one pass: an inspection visits every property of a translation file, and
     * they share their prefixes almost entirely — the whole of `status.*` asks the same single
     * word. Without it the same search would run once per property, on every keystroke.
     */
    fun isReached(
        key: String,
        scope: GlobalSearchScope,
        searchHelper: PsiSearchHelper,
        nsSeparator: String,
        keySeparator: String,
        literals: MutableMap<String, Set<String>>,
    ): Boolean {
        val bare = key.substringAfter(nsSeparator, key)
        return wordsOf(key, nsSeparator, keySeparator).any { word ->
            literals.getOrPut(word) { dynamicLiterals(word, scope, searchHelper) }
                .any { shapeOf(it)?.reaches(key, bare) == true }
        }
    }

    /** The text of every dynamic key literal [word] leads to that has a static part. */
    private fun dynamicLiterals(
        word: String,
        scope: GlobalSearchScope,
        searchHelper: PsiSearchHelper,
    ): Set<String> {
        val literals = mutableSetOf<String>()
        val languages = Extensions.LANG.extensionList
        searchHelper.processElementsWithWord(
            { element, _ ->
                val literal = languages.firstNotNullOfOrNull { it.resolveLiteral(element) }
                val text = literal?.text?.unQuote()
                if (text != null && shapeOf(text) != null) literals.add(text)
                true
            },
            scope,
            word,
            UsageSearchContext.ANY,
            true
        )
        return literals
    }

    /**
     * The shape of a key literal, or `null` when nothing in it is computed or nothing in it is
     * static — `t(`${'$'}{name}`)` says nothing about the keys it reaches.
     */
    internal fun shapeOf(text: String): Shape? {
        if (!text.contains(INTERPOLATION)) return null
        val lastEnd = text.indexOf('}', text.lastIndexOf(INTERPOLATION))
        val tail = if (lastEnd < 0) "" else text.substring(lastEnd + 1)
        return Shape(text.substringBefore(INTERPOLATION), tail).takeIf { it.head.isNotEmpty() || it.tail.isNotEmpty() }
    }

    /** The distinct words to search for, on behalf of all of [keys]. */
    internal fun searchWords(keys: List<String>, nsSeparator: String, keySeparator: String): List<String> =
        keys.flatMap { wordsOf(it, nsSeparator, keySeparator) }.distinct()

    /** The words leading to a literal that may reach [key]: its prefixes, then its suffixes. */
    private fun wordsOf(key: String, nsSeparator: String, keySeparator: String): List<String> =
        prefixesOf(key, nsSeparator, keySeparator) + suffixesOf(key, nsSeparator, keySeparator)

    /**
     * The prefixes of [key] a dynamic literal could carry, longest first: the key with its last
     * segment dropped, then the one before, and so on.
     *
     * A prefix holding no separator at all is left out. It would be a bare first segment —
     * `status`, `title` — matching a word far too common to search the whole project for, and
     * the head it would find (`status.`) says nothing about which namespace it belongs to. The
     * cost is that a dynamic key written under an implicit namespace and only one level deep
     * (`t(`status.${'$'}{kind}`)` after `useTranslation('common')`) is not seen.
     */
    internal fun prefixesOf(key: String, nsSeparator: String, keySeparator: String): List<String> {
        val prefixes = mutableListOf<String>()
        var current = key
        while (current.contains(keySeparator)) {
            current = current.substringBeforeLast(keySeparator)
            if (current.contains(nsSeparator) || current.contains(keySeparator)) prefixes.add(current)
        }
        return prefixes
    }

    /**
     * The suffixes of [key] a dynamic literal could end with, longest first: the key without its
     * namespace, then with its first segment dropped, and so on.
     *
     * Same rule as [prefixesOf]: a suffix of one segment — `ok`, `label` — is left out, as a word
     * far too common to search for. A dynamic namespace in front of a one-segment key
     * (`t(`${'$'}{ns}:title`)`) is therefore not seen.
     */
    internal fun suffixesOf(key: String, nsSeparator: String, keySeparator: String): List<String> {
        val suffixes = mutableListOf<String>()
        var current = key.substringAfter(nsSeparator, key)
        while (current.contains(keySeparator)) {
            suffixes.add(current)
            current = current.substringAfter(keySeparator)
        }
        return suffixes
    }
}
