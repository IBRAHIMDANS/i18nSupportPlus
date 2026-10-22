package com.ibrahimdans.i18n.plugin.format

import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YamlTranslationTreeTest {

    private fun parse(text: String): YamlTranslationTree = YamlTranslationTree.parse(text)!!

    private fun Tree<ParsedNode>.names(): List<String> = entries()!!.map { it.first }

    private fun String.at(span: TextSpan?): String = substring(span!!.start, span.end)

    @Test
    fun `mappings are trees, scalars and sequences leaves with their value`() {
        val text = "a: A\nn: 42\nq: 'it''s'\nd: \"x\\ty\"\nlist:\n  - one\n  - two\nflow: [1, 2]\no:\n  c: C\n"
        val tree = parse(text)
        assertEquals(listOf("a", "n", "q", "d", "list", "flow", "o"), tree.names())
        assertEquals(
            listOf("A", "42", "it's", "x\ty", "- one\n  - two", "[1, 2]", null),
            tree.entries().map { it.second.value().value },
        )
        assertTrue(tree.findChild("list")!!.isLeaf())
        assertEquals("C", tree.findChild("o")!!.findChild("c")!!.value().value)
    }

    @Test
    fun `a key without value is no child, an empty string is`() {
        val tree = parse("none:\nempty: ''\nnull: ~\n")
        assertEquals(listOf("empty", "null"), tree.names())
        assertNull(tree.findChild("none"))
        assertEquals("", tree.findChild("empty")!!.value().value)
        assertEquals("~", tree.findChild("null")!!.value().value)
    }

    @Test
    fun `quoted keys are read unquoted, findChildren filters on the prefix`() {
        val tree = parse("'home': 1\n\"help\": 2\nmenu: 3\n")
        assertEquals(listOf("home", "help"), tree.findChildren("h").map { it.value().key })
        assertEquals("1", tree.findChild("home")!!.value().value)
    }

    @Test
    fun `only the first document is read, a root other than a mapping has no children`() {
        assertEquals(listOf("a"), parse("a: 1\n---\nb: 2\n").names())
        for (text in listOf("", "just text\n", "- a\n- b\n")) {
            val tree = parse(text)
            assertTrue(tree.isTree(), text)
            assertEquals(emptyList<Any>(), tree.entries(), text)
        }
    }

    @Test
    fun `spans cover keys and values, quotes included, over several lines`() {
        val text = "# header\nmenu:\n  title: \"Menu\"\n  plain: two\n    lines\n  block: |\n    first\n    second\n\n  # trailing comment\nemoji: \uD83D\uDE00 smile\nafter: 'x'\n"
        val tree = parse(text)
        val menu = tree.findChild("menu")!!
        assertEquals("menu", text.at(menu.value().keySpan))
        assertEquals("title: \"Menu\"\n  plain: two\n    lines\n  block: |\n    first\n    second", text.at(menu.value().valueSpan))
        val title = menu.findChild("title")!!.value()
        assertEquals("title", text.at(title.keySpan))
        assertEquals("\"Menu\"", text.at(title.valueSpan))
        assertEquals("two\n    lines", text.at(menu.findChild("plain")!!.value().valueSpan))
        val block = menu.findChild("block")!!.value()
        assertEquals("first\nsecond\n", block.value)
        assertEquals("|\n    first\n    second", text.at(block.valueSpan))
        assertEquals("\uD83D\uDE00 smile", text.at(tree.findChild("emoji")!!.value().valueSpan))
        val after = tree.findChild("after")!!.value()
        assertEquals("after", text.at(after.keySpan))
        assertEquals("'x'", text.at(after.valueSpan))
    }

    @Test
    fun `invalid text gives null`() {
        for (text in listOf("a: [1, 2\n", "a: 'open\n", "a: b: c\n", "a:\n  b: 1\n c: 2\n", "{a: 1\n")) {
            assertNull(YamlTranslationTree.parse(text), text)
        }
    }
}
