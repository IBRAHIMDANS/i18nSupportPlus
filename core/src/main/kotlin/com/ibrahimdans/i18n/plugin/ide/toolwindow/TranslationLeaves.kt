package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.tree.Tree

/**
 * How [TranslationLeaves] reads the nodes of a `Tree<N>`: the name a child is listed under, and
 * the text of a leaf. The PSI trees and the parsed ones answer each in their own way.
 */
interface LeafReader<N> {

    /** The key name of [node], one of the children `findChildren` lists, or null when it has none. */
    fun nameOf(node: Tree<N>): String?

    /** The text value of the leaf [node]. */
    fun valueOf(node: Tree<N>): String
}

/** The leaves of a translation tree, flattened into the key -> (locale -> value) map the tool window shows. */
object TranslationLeaves {

    /**
     * Recursively collects the leaf values of [tree] into [result], under [locale].
     * [nsPrefix] is prepended to each full key, e.g. "common:" or "" for the default namespace;
     * levels are joined the way [KeySpelling] spells them.
     *
     * A child is reached through `tree.findChild(name)` rather than taken from `findChildren`:
     * the PSI trees list key elements there, and only the value tells a leaf from a nested object.
     */
    fun <N> collect(
        config: Config,
        tree: Tree<N>,
        nsPrefix: String,
        locale: String,
        reader: LeafReader<N>,
        result: MutableMap<String, MutableMap<String, String>>,
    ) = collect(config, tree, "", nsPrefix, locale, reader, result)

    private fun <N> collect(
        config: Config,
        tree: Tree<N>,
        prefix: String,
        nsPrefix: String,
        locale: String,
        reader: LeafReader<N>,
        result: MutableMap<String, MutableMap<String, String>>,
    ) {
        for ((childName, valueTree) in namedChildren(tree, reader)) {
            val fullPath = KeySpelling.child(config, prefix, childName)
            if (valueTree == null || valueTree.isLeaf()) {
                val value = if (valueTree != null) reader.valueOf(valueTree) else ""
                result.getOrPut("$nsPrefix$fullPath") { mutableMapOf() }[locale] = value
            } else {
                collect(config, valueTree, fullPath, nsPrefix, locale, reader, result)
            }
        }
    }

    /**
     * The children of [tree] as (name, value node), the value being null when the key holds none.
     *
     * JSON and YAML key nodes are named by [reader], as they always were. A format whose key nodes
     * the reader cannot name — a PHP array's `StringLiteralExpression`, the PHP plugin being
     * optional — names its children itself through [Tree.entries]: without that, a Laravel
     * project's keys were all skipped and the tool window showed none.
     */
    private fun <N> namedChildren(tree: Tree<N>, reader: LeafReader<N>): List<Pair<String, Tree<N>?>> {
        val children = tree.findChildren("")
        val named = children.mapNotNull { child ->
            reader.nameOf(child)?.takeIf { it.isNotEmpty() }?.let { it to tree.findChild(it) }
        }
        if (named.isNotEmpty() || children.isEmpty()) return named
        return tree.entries()?.filter { (name, _) -> name.isNotEmpty() } ?: emptyList()
    }
}
