package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.plugin.utils.TextSpan

/**
 * One node of a parsed source file, whatever parsed it: the IDE's PSI in the plugin, another
 * parser outside it. Key extraction written against it runs on either.
 *
 * Two nodes are equal when they stand for the same node of the same tree, so a node can key a map
 * or be found again among its parent's [children]. An adapter answers that from what identifies
 * the node in its own parser, never from [text] or [span] alone.
 */
interface CodeNode {

    /** The parser's name for the kind of node, e.g. `JS:STRING_LITERAL` or `string_fragment`. */
    val type: String

    /** The source text the node covers. */
    val text: String

    /** Where the node lies in its file, in chars — an adapter whose parser counts bytes converts. */
    val span: TextSpan

    /** The enclosing node, null for the root of the file. */
    val parent: CodeNode?

    /** The nodes directly under this one, in source order, tokens included. */
    val children: List<CodeNode>

    /** The path of the file the node belongs to, null when it has none (a file held in memory). */
    val file: String?
}
