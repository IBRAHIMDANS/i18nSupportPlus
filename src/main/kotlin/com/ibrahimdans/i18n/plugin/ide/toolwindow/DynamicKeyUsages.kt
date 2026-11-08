package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.usages.DynamicReach

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
 *
 * The literals themselves are read once, by the index of the code's usages
 * (`CodeUsageIndexService.dynamicallyReached`): what is left here is which words stand for a key.
 */
object DynamicKeyUsages {

    /** The text a dynamic literal must hold for its search word to be worth searching: see [prefixesOf]. */
    private const val INTERPOLATION = "\${"

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
     * A bare first segment — `errors`, `title` — is a word far too common to search the whole
     * project for. For a key of the default namespace, written without one, it is searched
     * followed by the separator and an interpolation instead (`errors.${'$'}{`): that is the shape
     * of `t(`errors.${'$'}{code}`)` after `useTranslation()`, the usual message per error code, and
     * the text occurs nowhere else. Its head says nothing about a namespace, so
     * [DynamicReach.Shape.reaches] lets it reach the default namespace's keys only. A key of
     * another namespace keeps the old rule: its first segment alone is left out.
     */
    internal fun prefixesOf(key: String, nsSeparator: String, keySeparator: String): List<String> {
        val prefixes = mutableListOf<String>()
        val defaultNamespace = nsSeparator.isEmpty() || !key.contains(nsSeparator)
        var current = key
        while (current.contains(keySeparator)) {
            current = current.substringBeforeLast(keySeparator)
            when {
                current.contains(nsSeparator) || current.contains(keySeparator) -> prefixes.add(current)
                defaultNamespace && current.isNotEmpty() -> prefixes.add(current + keySeparator + INTERPOLATION)
            }
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
