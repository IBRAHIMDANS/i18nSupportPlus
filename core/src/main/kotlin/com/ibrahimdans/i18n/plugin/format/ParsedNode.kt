package com.ibrahimdans.i18n.plugin.format

import com.ibrahimdans.i18n.plugin.tree.Tree

/**
 * A range of a parsed text, in chars: [start] included, [end] excluded — the convention of
 * `String.substring` and of the platform's `TextRange`.
 */
data class TextSpan(val start: Int, val end: Int)

/**
 * One node of a translation file read without the PSI.
 *
 * [key] is the name the parent lists this node under, null for the root. [value] is the text a
 * leaf stands for: a string decoded from its quotes and escapes, the source text of any other
 * scalar or of an array; null for an object, whose content is its children.
 * [keySpan] and [valueSpan] locate the key and the value in the source, quotes included — the
 * ranges the PSI elements of the key and of the value cover. The root has no [keySpan].
 */
data class ParsedNode(val key: String?, val value: String?, val keySpan: TextSpan?, val valueSpan: TextSpan)

/**
 * A [Tree] over [ParsedNode]s, built once from the whole file:
 * [children] is null for a leaf, the object's entries in source order otherwise.
 *
 * Unlike the PSI trees, whose [findChildren] hands back the key elements, every child here is the
 * node itself: its [ParsedNode.key] is the name.
 */
abstract class ParsedNodeTree(
    private val node: ParsedNode,
    private val children: List<ParsedNodeTree>?,
) : Tree<ParsedNode> {
    override fun value(): ParsedNode = node
    override fun isTree(): Boolean = children != null

    // The first one on a duplicated key, as the PSI's findProperty and getKeyValueByKey answer.
    override fun findChild(name: String): ParsedNodeTree? = children?.firstOrNull { it.node.key == name }

    override fun findChildren(prefix: String): List<ParsedNodeTree> = listed().filter { it.name().startsWith(prefix) }

    override fun entries(): List<Pair<String, ParsedNodeTree>> = listed().map { it.name() to it }

    /** The children [findChildren] and [entries] list: all of them unless a format leaves some out. */
    protected open fun listed(): List<ParsedNodeTree> = children.orEmpty()

    private fun name(): String = node.key.orEmpty()
}
