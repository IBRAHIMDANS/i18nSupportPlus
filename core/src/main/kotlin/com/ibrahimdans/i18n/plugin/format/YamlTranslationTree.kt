package com.ibrahimdans.i18n.plugin.format

import com.ibrahimdans.i18n.plugin.utils.TextSpan
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.common.FlowStyle
import org.snakeyaml.engine.v2.common.ScalarStyle
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode

/**
 * A YAML translation file read with snakeyaml-engine, the same tree `YamlElementTree` gives over
 * the PSI: the first document's root mapping and the mappings under it are the trees, a scalar or
 * a sequence a leaf. A document whose root is no mapping is a tree without children.
 *
 * A key with nothing after its colon has no value element in the PSI, so it is no child here
 * either; a key that is not a scalar is skipped.
 */
class YamlTranslationTree private constructor(
    node: ParsedNode,
    children: List<YamlTranslationTree>?,
) : ParsedNodeTree(node, children) {

    companion object {
        // The default refuses files over 3 MB, and a refused file would read as an invalid one.
        private val settings = LoadSettings.builder().setCodePointLimit(Int.MAX_VALUE).build()

        /** The tree of [text]'s first document; null for text that is not YAML. */
        fun parse(text: String): YamlTranslationTree? =
            try {
                val reader = Reader(text)
                when (val root = Compose(settings).composeAllFromString(text).firstOrNull()) {
                    is MappingNode -> reader.read(root, key = null, keySpan = null)
                    else -> YamlTranslationTree(ParsedNode(null, null, null, root?.let(reader::span) ?: TextSpan(0, text.length)), emptyList())
                }
            } catch (e: YamlEngineException) {
                null
            }
    }

    /**
     * Builds the tree of one parsed text. snakeyaml-engine's marks count code points: [offsets]
     * turns them into char offsets, which only differ past a character outside the BMP (an emoji).
     */
    private class Reader(private val text: String) {
        private val offsets: IntArray? =
            if (text.codePointCount(0, text.length) == text.length) null
            else IntArray(text.codePointCount(0, text.length) + 1).also { table ->
                var offset = 0
                for (codePoint in table.indices) {
                    table[codePoint] = offset
                    if (offset < text.length) offset += Character.charCount(text.codePointAt(offset))
                }
            }

        fun read(node: Node, key: String?, keySpan: TextSpan?): YamlTranslationTree {
            val span = span(node)
            return when (node) {
                is MappingNode -> YamlTranslationTree(
                    ParsedNode(key, null, keySpan, span),
                    node.value.mapNotNull { tuple ->
                        val name = tuple.keyNode as? ScalarNode ?: return@mapNotNull null
                        tuple.valueNode.takeUnless(::isAbsent)?.let { read(it, name.value, span(name)) }
                    },
                )
                is ScalarNode -> leaf(key, node.value, keySpan, span)
                // A sequence: a leaf whose value is its source text.
                else -> leaf(key, text.substring(span.start, span.end), keySpan, span)
            }
        }

        private fun leaf(key: String?, value: String, keySpan: TextSpan?, span: TextSpan): YamlTranslationTree =
            YamlTranslationTree(ParsedNode(key, value, keySpan, span), null)

        fun span(node: Node): TextSpan = TextSpan(offset(node.startMark.get().index), end(node))

        /**
         * Where [node] ends in the source. A block collection's end mark is the next token's start,
         * past the blank lines and comments that follow it, and a block scalar's takes in its line
         * breaks: both end on their last character instead, as their PSI element does.
         */
        private fun end(node: Node): Int = when {
            node is MappingNode && node.flowStyle == FlowStyle.BLOCK && node.value.isNotEmpty() ->
                node.value.last().let { end(if (isAbsent(it.valueNode)) it.keyNode else it.valueNode) }
            node is SequenceNode && node.flowStyle == FlowStyle.BLOCK && node.value.isNotEmpty() -> end(node.value.last())
            node is ScalarNode && (node.scalarStyle == ScalarStyle.LITERAL || node.scalarStyle == ScalarStyle.FOLDED) -> {
                val start = offset(node.startMark.get().index)
                var end = offset(node.endMark.get().index)
                while (end > start && text[end - 1].isWhitespace()) end--
                end
            }
            else -> offset(node.endMark.get().index)
        }

        private fun offset(codePoint: Int): Int = offsets?.get(codePoint) ?: codePoint

        /** The empty scalar standing for a value left out (`key:`), which the PSI has no element for. */
        private fun isAbsent(node: Node): Boolean = node is ScalarNode && node.isPlain && node.value.isEmpty()
    }
}
