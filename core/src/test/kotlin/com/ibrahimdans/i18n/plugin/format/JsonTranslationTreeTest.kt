package com.ibrahimdans.i18n.plugin.format

import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonTranslationTreeTest {

    private fun parse(text: String): JsonTranslationTree = JsonTranslationTree.parse(text)!!

    private fun Tree<ParsedNode>.names(): List<String> = entries()!!.map { it.first }

    private fun String.at(span: TextSpan?): String = substring(span!!.start, span.end)

    @Test
    fun `objects are trees, everything else a leaf with its value`() {
        val tree = parse("""{"a": "A", "n": 42, "b": true, "z": null, "list": [1, "x"], "o": {"c": "C"}}""")
        assertTrue(tree.isTree())
        assertEquals(listOf("a", "n", "b", "z", "list", "o"), tree.names())
        assertEquals(
            listOf("A", "42", "true", "null", """[1, "x"]""", null),
            tree.entries().map { it.second.value().value },
        )
        assertTrue(tree.findChild("list")!!.isLeaf())
        assertEquals("C", tree.findChild("o")!!.findChild("c")!!.value().value)
        assertNull(tree.findChild("missing"))
    }

    @Test
    fun `strings are decoded from their escapes`() {
        val tree = parse("""{"k\"ey": "a\nb \u00e9"}""")
        assertEquals("a\nb é", tree.findChild("k\"ey")!!.value().value)
    }

    @Test
    fun `findChildren filters on the prefix, findChild takes the first duplicate`() {
        val tree = parse("""{"home": "1", "help": "2", "menu": "3", "home": "4"}""")
        assertEquals(listOf("home", "help", "home"), tree.findChildren("h").map { it.value().key })
        assertEquals("1", tree.findChild("home")!!.value().value)
    }

    @Test
    fun `the root schema is listed nowhere but found by name, a nested one is a key`() {
        val tree = parse("""{"${'$'}schema": "s", "hello": "Hello", "form": {"${'$'}schema": "x"}}""")
        assertEquals(listOf("hello", "form"), tree.names())
        assertEquals(0, tree.findChildren("${'$'}").size)
        assertNotNull(tree.findChild("${'$'}schema"))
        assertEquals(listOf("${'$'}schema"), tree.findChild("form")!!.names())
    }

    @Test
    fun `spans cover keys and values, quotes included, over several lines`() {
        val text = "{\n  \"menu\": {\n    \"title\": \"Menu \\\"main\\\"\",\n    \"count\": 12\n  },\n  \"emoji\": \"\uD83D\uDE00\", \"after\": \"x\"\n}"
        val tree = parse(text)
        assertEquals(TextSpan(0, text.length), tree.value().valueSpan)
        assertNull(tree.value().keySpan)
        val menu = tree.findChild("menu")!!
        assertEquals("\"menu\"", text.at(menu.value().keySpan))
        assertEquals("{\n    \"title\": \"Menu \\\"main\\\"\",\n    \"count\": 12\n  }", text.at(menu.value().valueSpan))
        val title = menu.findChild("title")!!.value()
        assertEquals("\"title\"", text.at(title.keySpan))
        assertEquals("\"Menu \\\"main\\\"\"", text.at(title.valueSpan))
        assertEquals("12", text.at(menu.findChild("count")!!.value().valueSpan))
        assertEquals("\"\uD83D\uDE00\"", text.at(tree.findChild("emoji")!!.value().valueSpan))
        assertEquals("\"after\"", text.at(tree.findChild("after")!!.value().keySpan))
    }

    @Test
    fun `a leaf has no children`() {
        val leaf = parse("""{"a": "A"}""").findChild("a")!!
        assertFalse(leaf.isTree())
        assertNull(leaf.findChild("x"))
        assertEquals(emptyList<Any>(), leaf.findChildren(""))
    }

    @Test
    fun `invalid text or a root other than an object gives null`() {
        for (text in listOf("", "{", """{"a": }""", """{"a": "A"} trailing""", """{"a": "A"} {}""", "[1, 2]", "\"text\"", "{'a': 1}")) {
            assertNull(JsonTranslationTree.parse(text), text)
        }
    }
}
