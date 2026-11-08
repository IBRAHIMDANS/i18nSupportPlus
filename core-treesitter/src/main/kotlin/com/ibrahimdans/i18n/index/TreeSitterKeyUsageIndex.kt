package com.ibrahimdans.i18n.index

import com.ibrahimdans.i18n.extract.ConstantKeys
import com.ibrahimdans.i18n.extract.i18next.I18NEXT_FUNCTIONS
import com.ibrahimdans.i18n.extract.i18next.i18nextExtractors
import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import com.ibrahimdans.i18n.plugin.KeyUsage
import com.ibrahimdans.i18n.plugin.KeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.parser.CodeKeyExtractor
import com.ibrahimdans.i18n.plugin.parser.KeyContext
import com.ibrahimdans.i18n.plugin.parser.KeyResolver
import com.ibrahimdans.i18n.plugin.tree.ContextKey
import com.ibrahimdans.i18n.plugin.tree.ContextOption
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.usages.CallSiteIndex
import com.ibrahimdans.i18n.plugin.usages.DynamicReach
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
 * hook counts in the hook's namespaces and the [fallbackNamespaces]. A call passing a constant,
 * `t(KEYS.save)`, counts for the keys the constant holds, at its argument ([ConstantKeys]). Translation
 * values linking to a key (vue-i18n's `@:key`) are not read: no extractor of this module reads
 * translation files.
 *
 * Calls are serialized: an index is safe to share between threads, not fast to query from many.
 *
 * @param config the settings the keys are read with: separators, default namespaces, flat keys
 * @param fallbackNamespaces i18next's `fallbackNS`, in order
 * @param extractors what the files are read with: i18next's calls by default
 * @param imports where a constant imported from another file is read, none by default
 * @param keyScopes the scopes a key may lead with, as the plugin's technologies declare them: a Laravel
 * package's override, `courier::messages`, reads `courier::messages.sent` under that namespace. None by default.
 */
class TreeSitterKeyUsageIndex(
    private val config: Config = Config(),
    fallbackNamespaces: List<String> = config.fallbackNamespaces(),
    functionNames: Set<String> = I18NEXT_FUNCTIONS,
    private val extractors: List<CodeKeyExtractor> = i18nextExtractors(functionNames),
    private val imports: ConstantKeys.Imports = ConstantKeys.Imports.NONE,
    keyScopes: Set<String> = emptySet(),
) : KeyUsageIndex {

    private val context = KeyContext(config, keyScopes = { keyScopes }, fallbackNamespaces = { fallbackNamespaces })
    private val defaultNamespaces = config.defaultNamespaces()

    /** The call sites of each indexed file, under their key paths. */
    private val sites = CallSiteIndex<CallSite>()

    /** (Re)indexes the file at [path], whose content is [text]. */
    @Synchronized
    fun update(path: String, text: String) {
        val root = TreeSitterCodeNode.parseTsx(text, path)
        val sites = ConstantKeys.findKeys(text, root, extractors, imports).mapNotNull { (node, rawKey) ->
            val key = KeyResolver.parse(rawKey, context) ?: return@mapNotNull null
            CallSite(
                KeyUsage(path, TextSpan(node.span.start, node.span.end)),
                key.compositeKey.map { it.text },
                key.lookupNamespaces(),
                // As the code writes it: the literal's text, or the msgid built for a source-text `<Trans>`.
                if (key.isDynamic) null else rawKey.keyElements.joinToString("") { it.text },
                if (key.isDynamic) DynamicReach.shapeOf(node.text.unQuote()) else null,
                key.context,
            )
        }
        this.sites.update(path, sites)
    }

    /** Drops the file at [path] from the index, if it was indexed. */
    @Synchronized
    fun remove(path: String) = sites.remove(path)

    /**
     * [key] spelled as [KeyUsageIndex] states, `common:menu.home`; a plural form stands for the key
     * the code writes, as `IndirectKeyUsages.usageQuery` reads it. A key spelled as an i18next context
     * variant, `friend_male`, is also used by the calls reading its base under that context —
     * `t('friend', { context: gender })` — as the plugin's `IndirectKeyUsages` counts them.
     */
    @Synchronized
    override fun usages(key: String): List<KeyUsage> {
        val namespace = KeySpelling.namespaceOf(key)
        val path = pathOf(key)
        val own = pathsOf(key).flatMap { sites.under(it) }
        val variantOf = variantOf(path)?.let { (basePath, context) ->
            sites.at(basePath).filter { ContextKey.reads(it.context, context) }
        }.orEmpty()
        return (own + variantOf).filter { it.worksIn(namespace) }.map { it.usage }.distinct()
    }

    /** The segments of [key], plural suffix stripped: the path a call writes for it. */
    private fun pathOf(key: String): List<String> =
        KeySpelling.segmentsOf(PluralKey.stripSuffix(key, config.pluralSeparator), config)

    /**
     * The paths a call may write for [key]: its [pathOf], and for a key with an empty level — a
     * Laravel JSON phrase ending with the separator, `Saved.` — the whole key as one level too,
     * the one a call reading it unsplit writes.
     */
    private fun pathsOf(key: String): List<List<String>> {
        val path = pathOf(key)
        if (path.size < 2 || path.none { it.isEmpty() }) return listOf(path)
        return listOf(path, listOf(KeySpelling.pathOf(PluralKey.stripSuffix(key, config.pluralSeparator))))
    }

    /**
     * The base path and context of a [path] spelled as a context variant, `friend_male` → `friend`
     * and `male`; null when its last segment is none, or when the context separator is also the key
     * separator, the segments then holding no variant to tell apart.
     */
    private fun variantOf(path: List<String>): Pair<List<String>, String>? {
        if (config.keySeparator == ContextKey.SEPARATOR) return null
        val (base, context) = path.lastOrNull()?.let(ContextKey::split) ?: return null
        return path.dropLast(1) + base to context
    }

    /**
     * The rule the plugin's `DynamicKeyUsages` applies, [DynamicReach], over every dynamic call
     * site indexed: one rule, so `i18n-check` and the IDE agree on which keys are reached.
     */
    @Synchronized
    override fun dynamicallyReached(keys: Collection<String>): Set<String> {
        val shapes = sites.sites().mapNotNullTo(mutableSetOf()) { it.shape }
        return DynamicReach.reached(keys, shapes, config.nsSeparator, config.keySeparator)
    }

    /**
     * The call sites naming in full a key that none of [keys] is or holds — a typo, a key never
     * added — with the key as the code writes it. [keys] are spelled as [usages] reads them; a call
     * whose key is built at runtime is never one.
     */
    @Synchronized
    fun unknownUsages(keys: Collection<String>): List<KeyCall> {
        // Each level of each key, with the namespaces it lives in: a call may name a whole object.
        val known = mutableMapOf<List<String>, MutableSet<String?>>()
        // The context variants of each base path, with their namespaces: `friend` → (`male`, null).
        val variants = mutableMapOf<List<String>, MutableSet<Pair<String, String?>>>()
        for (key in keys) {
            val path = pathOf(key)
            val namespace = KeySpelling.namespaceOf(key)
            for (written in pathsOf(key)) for (depth in 1..written.size) known.getOrPut(written.subList(0, depth)) { mutableSetOf() } += namespace
            variantOf(path)?.let { (basePath, context) -> variants.getOrPut(basePath) { mutableSetOf() } += context to namespace }
        }
        return sites.sites()
            .filter { site ->
                site.key != null && known[site.path].orEmpty().none(site::worksIn) &&
                    variants[site.path].orEmpty().none { (context, namespace) -> ContextKey.reads(site.context, context) && site.worksIn(namespace) }
            }
            .map { KeyCall(it.key!!, it.usage) }
            .toList()
    }

    /**
     * A call site whose literal resolves to [path] in [namespaces] — none when it writes none and
     * sits under no hook — with its [key] as written, or, for a key built at runtime, the [shape] of
     * the keys it may produce ([DynamicReach.shapeOf]) — and the i18next [context] it reads the key under.
     */
    private inner class CallSite(
        val usage: KeyUsage,
        override val path: List<String>,
        val namespaces: List<String>,
        val key: String?,
        val shape: DynamicReach.Shape?,
        val context: ContextOption?,
    ) : CallSiteIndex.Site {

        /**
         * True when the call site works in [namespace], or in a default one for a key spelled
         * without: the check of the plugin's `ReferencesAccumulator.underExpectedNamespace`.
         */
        fun worksIn(namespace: String?): Boolean = namespaces.isEmpty() ||
            if (namespace == null) namespaces.any { it in defaultNamespaces } else namespace in namespaces
    }
}

/** A call naming [key] as the code writes it, at [usage]. */
data class KeyCall(val key: String, val usage: KeyUsage)
