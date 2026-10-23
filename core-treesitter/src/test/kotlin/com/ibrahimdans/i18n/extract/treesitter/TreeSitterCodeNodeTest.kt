package com.ibrahimdans.i18n.extract.treesitter

import com.ibrahimdans.i18n.plugin.parser.CodeNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TreeSitterCodeNodeTest {

    private fun CodeNode.descendants(): Sequence<CodeNode> = sequenceOf(this) + children.asSequence().flatMap { it.descendants() }

    private fun CodeNode.first(type: String): CodeNode = descendants().first { it.type == type }

    @Test
    fun `nodes carry the grammar's types, their text and their tokens`() {
        val root = TreeSitterCodeNode.parseTsx("t('a.b');")
        assertEquals("program", root.type)
        assertFalse(root.hasError)
        val call = root.first("call_expression")
        assertEquals("t('a.b')", call.text)
        assertEquals(listOf("identifier", "arguments"), call.children.map { it.type })
        assertEquals(listOf("(", "string", ")"), call.children[1].children.map { it.type })
        assertEquals(listOf("'", "string_fragment", "'"), root.first("string").children.map { it.type })
    }

    @Test
    fun `spans count chars, not UTF-8 bytes`() {
        // é is two bytes, the emoji four bytes and two chars: byte offsets would drift past them.
        val text = "const s = 'é😀'; t('ключ.a');"
        val root = TreeSitterCodeNode.parseTsx(text)
        val strings = root.descendants().filter { it.type == "string" }.toList()
        assertEquals(listOf("'é😀'", "'ключ.a'"), strings.map { it.text })
        strings.forEach { assertEquals(it.text, text.substring(it.span.start, it.span.end)) }
        assertEquals(text.length, root.span.end)
    }

    @Test
    fun `a node equals itself reached again, never another node`() {
        val root = TreeSitterCodeNode.parseTsx("t('a'); t('a');")
        val (first, second) = root.descendants().filter { it.type == "string" }.toList()
        val again = first.parent!!.parent!!.children[1].children[1]
        assertEquals(first, again)
        assertEquals(first.hashCode(), again.hashCode())
        assertNotEquals(first, second)
        assertTrue(first.parent!!.children.contains(first))
        assertNotEquals(first, TreeSitterCodeNode.parseTsx("t('a'); t('a');").first("string"))
    }

    @Test
    fun `the root has no parent, and a node knows its file`() {
        val root = TreeSitterCodeNode.parseTsx("t('a');", "src/A.tsx")
        assertNull(root.parent)
        assertEquals("src/A.tsx", root.first("string").file)
        assertNull(TreeSitterCodeNode.parseTsx("t('a');").file)
    }

    @Test
    fun `a syntax error is recovered from, not thrown`() {
        val root = TreeSitterCodeNode.parseTsx("t('a'; const = ;")
        assertTrue(root.hasError)
    }

    @Test
    fun `a text longer than the read buffer is parsed whole`() {
        val text = "t('a');\n".repeat(20_000) + "t('last');"
        val root = TreeSitterCodeNode.parseTsx(text)
        assertEquals("'last'", root.descendants().last { it.type == "string" }.text)
        assertFalse(root.hasError)
    }

    @Test
    fun `a lone surrogate counts as the one byte UTF-8 encodes it as`() {
        val text = "t('\uD800'); t('k');"
        val key = TreeSitterCodeNode.parseTsx(text).descendants().first { it.text == "'k'" }
        assertEquals(text.indexOf("'k'"), key.span.start)
    }

    @Test
    fun `JavaScript, TypeScript and JSX parse with the one grammar`() {
        val text = "import x from 'y'; const f = (a: string): number => 1; export const C = () => <p>{t('k')}</p>;"
        assertFalse(TreeSitterCodeNode.parseTsx(text).hasError)
    }
}
