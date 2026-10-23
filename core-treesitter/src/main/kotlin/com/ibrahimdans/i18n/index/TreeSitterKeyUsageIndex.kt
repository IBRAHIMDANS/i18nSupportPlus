package com.ibrahimdans.i18n.index

import com.ibrahimdans.i18n.extract.i18next.I18NEXT_FUNCTIONS
import com.ibrahimdans.i18n.extract.i18next.findKeys
import com.ibrahimdans.i18n.extract.i18next.i18nextExtractors
import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import com.ibrahimdans.i18n.plugin.KeyUsage
import com.ibrahimdans.i18n.plugin.KeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.parser.KeyContext
import com.ibrahimdans.i18n.plugin.parser.KeyResolver
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import com.ibrahimdans.i18n.plugin.utils.unQuote

/**
 * [KeyUsageIndex] outside the IDE: an inverted index from key to call sites, built by reading each
 * file with the tree-sitter i18next extractors and resolving its keys with [KeyResolver].
 *
 * The files are given one at a time, [update] when one is read or changed, [remove] when it is gone;
 * only that file's entries are rebuilt. Answers what the plugin's `IdeKeyUsageIndex` answers from the
 * IDE's word index: a call site counts for its key and for every key above it (`menu` for
 * `t('menu.home')`); one writing no namespace and under no hook counts in every namespace; one under a
 * hook counts in the hook's namespaces and the [fallbackNamespaces]. Translation values linking to a
 * key (vue-i18n's `@:key`) are not read: no extractor of this module reads translation files.
 *
 * Calls are serialized: an index is safe to share between threads, not fast to query from many.
 *
 * @param config the settings the keys are read with: separators, default namespaces, flat keys
 * @param fallbackNamespaces i18next's `fallbackNS`, in order
 */
class TreeSitterKeyUsageIndex(
    private val config: Config = Config(),
    fallbackNamespaces: List<String> = config.fallbackNamespaces(),
    functionNames: Set<String> = I18NEXT_FUNCTIONS,
) : KeyUsageIndex {

    private val context = KeyContext(config, fallbackNamespaces = { fallbackNamespaces })
    private val extractors = i18nextExtractors(functionNames)
    private val defaultNamespaces = config.defaultNamespaces()

    /** The call sites of each indexed file. */
    private val files = mutableMapOf<String, List<CallSite>>()

    /** The call sites under each key path, the paths above it included. */
    private val byPath = mutableMapOf<List<String>, MutableSet<CallSite>>()

    /** (Re)indexes the file at [path], whose content is [text]. */
    @Synchronized
    fun update(path: String, text: String) {
        remove(path)
        val root = TreeSitterCodeNode.parseTsx(text, path)
        val sites = findKeys(root, extractors).mapNotNull { (node, rawKey) ->
            val key = KeyResolver.parse(rawKey, context) ?: return@mapNotNull null
            CallSite(
                KeyUsage(path, TextSpan(node.span.start, node.span.end)),
                key.compositeKey.map { it.text },
                key.lookupNamespaces(),
                if (key.isDynamic) shapeOf(node.text.unQuote()) else null,
            )
        }
        files[path] = sites
        for (site in sites) for (depth in 1..site.path.size) {
            byPath.getOrPut(site.path.subList(0, depth)) { mutableSetOf() } += site
        }
    }

    /** Drops the file at [path] from the index, if it was indexed. */
    @Synchronized
    fun remove(path: String) {
        val sites = files.remove(path) ?: return
        for (site in sites) for (depth in 1..site.path.size) {
            val prefix = site.path.subList(0, depth)
            val left = byPath[prefix] ?: continue
            left -= site
            if (left.isEmpty()) byPath -= prefix
        }
    }

    /**
     * [key] spelled as [KeyUsageIndex] states, `common:menu.home`; a plural form stands for the key
     * the code writes, as `IndirectKeyUsages.usageQuery` reads it.
     */
    @Synchronized
    override fun usages(key: String): List<KeyUsage> {
        val namespace = KeySpelling.namespaceOf(key)
        val path = KeySpelling.segmentsOf(PluralKey.stripSuffix(key, config.pluralSeparator), config)
        return byPath[path].orEmpty().filter { it.worksIn(namespace) }.map { it.usage }.distinct()
    }

    /** The rule of the plugin's `DynamicKeyUsages`, over every dynamic call site indexed. */
    @Synchronized
    override fun dynamicallyReached(keys: Collection<String>): Set<String> {
        val shapes = files.values.flatMapTo(mutableSetOf()) { sites -> sites.mapNotNull { it.shape } }
        if (shapes.isEmpty()) return emptySet()
        return keys.filterTo(mutableSetOf()) { key ->
            val bare = key.substringAfter(config.nsSeparator, key)
            shapes.any { it.reaches(key, bare) }
        }
    }

    /**
     * A call site whose literal resolves to [path] in [namespaces] — none when it writes none and
     * sits under no hook — and, for a key built at runtime, the [shape] of the keys it may produce.
     */
    private inner class CallSite(val usage: KeyUsage, val path: List<String>, val namespaces: List<String>, val shape: Shape?) {

        /**
         * True when the call site works in [namespace], or in a default one for a key spelled
         * without: the check of the plugin's `ReferencesAccumulator.underExpectedNamespace`.
         */
        fun worksIn(namespace: String?): Boolean = namespaces.isEmpty() ||
            if (namespace == null) namespaces.any { it in defaultNamespaces } else namespace in namespaces
    }

    /** The static ends of a dynamic key literal: a key it can produce starts with [head] and ends with [tail]. */
    private data class Shape(val head: String, val tail: String) {
        fun reaches(key: String, bare: String): Boolean =
            (key.startsWith(head) || bare.startsWith(head)) && (key.endsWith(tail) || bare.endsWith(tail))
    }

    private companion object {

        const val INTERPOLATION = "\${"

        /** `DynamicKeyUsages.shapeOf`: null when nothing in [text] is computed or nothing is static. */
        fun shapeOf(text: String): Shape? {
            if (!text.contains(INTERPOLATION)) return null
            val lastEnd = text.indexOf('}', text.lastIndexOf(INTERPOLATION))
            val tail = if (lastEnd < 0) "" else text.substring(lastEnd + 1)
            return Shape(text.substringBefore(INTERPOLATION), tail).takeIf { it.head.isNotEmpty() || it.tail.isNotEmpty() }
        }
    }
}
