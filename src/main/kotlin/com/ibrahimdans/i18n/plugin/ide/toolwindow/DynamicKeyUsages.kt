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
 * The rule is deliberately one of *prefix*, not of resolution: what a template literal will
 * hold is unknown until it runs, so every key under its static head is treated as reachable.
 * That over-approximates — a genuinely dead `status.obsolete` is spared as well — which is the
 * side to err on when the alternative is offering a live key for deletion.
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
     * The subset of [keys] some dynamic key literal can reach.
     *
     * One search per distinct prefix rather than per key: the three `status.*` keys share the
     * single word `deposit-box:status`, and the scan runs over every key a project holds.
     */
    fun reachedKeys(
        keys: List<String>,
        scope: GlobalSearchScope,
        searchHelper: PsiSearchHelper,
        nsSeparator: String,
        keySeparator: String,
    ): Set<String> {
        if (keys.isEmpty()) return emptySet()
        val heads = staticHeads(searchWords(keys, nsSeparator, keySeparator), scope, searchHelper)
        if (heads.isEmpty()) return emptySet()

        return keys.filterTo(mutableSetOf()) { key ->
            val bare = key.substringAfter(nsSeparator, key)
            heads.any { key.startsWith(it) || bare.startsWith(it) }
        }
    }

    /** The static head of every dynamic key literal [words] leads to. */
    private fun staticHeads(
        words: List<String>,
        scope: GlobalSearchScope,
        searchHelper: PsiSearchHelper,
    ): Set<String> {
        val heads = mutableSetOf<String>()
        val languages = Extensions.LANG.extensionList
        for (word in words) {
            searchHelper.processElementsWithWord(
                { element, _ ->
                    val literal = languages.firstNotNullOfOrNull { it.resolveLiteral(element) }
                    val text = literal?.text?.unQuote()
                    if (text != null && text.contains(INTERPOLATION)) {
                        heads.add(text.substringBefore(INTERPOLATION))
                    }
                    true
                },
                scope,
                word,
                UsageSearchContext.ANY,
                true
            )
        }
        return heads
    }

    /** The distinct words to search for, on behalf of all of [keys]. */
    internal fun searchWords(keys: List<String>, nsSeparator: String, keySeparator: String): List<String> =
        keys.flatMap { prefixesOf(it, nsSeparator, keySeparator) }.distinct()

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
}
