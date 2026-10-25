package com.ibrahimdans.i18n.extract.treesitter

import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import org.treesitter.TSInputEncoding
import org.treesitter.TSLanguage
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TSReader
import org.treesitter.TreeSitterTsx

/**
 * A [CodeNode] over a tree-sitter node: what the extractors read outside the IDE, where no PSI exists.
 *
 * tree-sitter counts UTF-8 bytes, [CodeNode.span] counts chars: [Source] converts. Two adapters are
 * equal when they wrap the same node of the same parsed file — the binding re-creates its node
 * objects on each access, so the node's id decides, never the Java reference.
 *
 * A child keeps the adapter it was reached from as its [parent]: walking back up then lands on
 * the nodes already built, their [children] included, instead of wrapping each ancestor anew.
 */
class TreeSitterCodeNode private constructor(
    private val node: TSNode,
    private val source: Source,
    private val knownParent: TreeSitterCodeNode? = null,
) : CodeNode {

    override val type: String by lazy(LazyThreadSafetyMode.NONE) { node.type }

    override val span: TextSpan by lazy(LazyThreadSafetyMode.NONE) {
        TextSpan(source.charOffset(node.startByte), source.charOffset(node.endByte))
    }

    override val text: String get() = source.text.substring(span.start, span.end)

    override val parent: CodeNode? by lazy(LazyThreadSafetyMode.NONE) {
        knownParent ?: node.parent.takeUnless { it.isNull }?.let { TreeSitterCodeNode(it, source) }
    }

    override val children: List<CodeNode> by lazy(LazyThreadSafetyMode.NONE) {
        (0 until node.childCount).map { TreeSitterCodeNode(node.getChild(it), source, this) }
    }

    override val file: String? get() = source.file

    /** True when the parser had to recover from a syntax error somewhere under this node. */
    val hasError: Boolean get() = node.hasError()

    override fun equals(other: Any?): Boolean =
        other is TreeSitterCodeNode && other.source === source && other.node == node

    override fun hashCode(): Int = node.hashCode()

    override fun toString(): String = "$type$span"

    /** The text of one parsed file and the char offset of each of its UTF-8 bytes. */
    private class Source(val text: String, val file: String?, val bytes: ByteArray) {

        // Null for an ASCII text, whose byte and char offsets are the same.
        private val charOffsets: IntArray? = if (bytes.size == text.length) null else charOffsets(text, bytes.size)

        fun charOffset(byteOffset: Int): Int = charOffsets?.get(byteOffset) ?: byteOffset
    }

    companion object {

        private val TSX: TSLanguage by lazy { TreeSitterTsx() }

        /**
         * The root of [text] parsed with the TSX grammar, which reads JavaScript, TypeScript and JSX
         * alike. A syntax error does not fail the parse: tree-sitter recovers around it, see [hasError].
         *
         * @param file the path [text] was read from, null for a text held in memory
         */
        fun parseTsx(text: String, file: String? = null): TreeSitterCodeNode {
            // Parsed from bytes encoded here rather than with parseString, whose offsets would count
            // the JNI's modified UTF-8: a character outside the BMP is 6 bytes there, 4 in real UTF-8.
            val bytes = text.toByteArray(Charsets.UTF_8)
            val reader = TSReader { buffer, offset, _ ->
                val count = minOf(buffer.size, bytes.size - offset).coerceAtLeast(0)
                System.arraycopy(bytes, offset, buffer, 0, count)
                count
            }
            val tree = TSParser().use { parser ->
                parser.setLanguage(TSX)
                parser.parse(ByteArray(BUFFER_SIZE), null, reader, TSInputEncoding.TSInputEncodingUTF8)
            } ?: error("tree-sitter returned no tree")
            // The tree outlives the parser, and each node keeps its tree alive.
            return TreeSitterCodeNode(tree.rootNode, Source(text, file, bytes))
        }

        private const val BUFFER_SIZE = 64 * 1024

        /**
         * The char offset of each byte offset of [text] encoded in UTF-8, the end included. A
         * multi-byte character maps all its bytes to its first char; tree-sitter only ever reports
         * the first. An unpaired surrogate is encoded as one byte (`?`), as [String.toByteArray] does.
         */
        private fun charOffsets(text: String, byteCount: Int): IntArray {
            val offsets = IntArray(byteCount + 1)
            var byte = 0
            var char = 0
            while (char < text.length) {
                val c = text[char]
                val pair = c.isHighSurrogate() && char + 1 < text.length && text[char + 1].isLowSurrogate()
                val length = when {
                    pair -> 4
                    c.isSurrogate() || c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    else -> 3
                }
                offsets.fill(char, byte, byte + length)
                byte += length
                char += if (pair) 2 else 1
            }
            offsets[byteCount] = text.length
            return offsets
        }
    }
}
