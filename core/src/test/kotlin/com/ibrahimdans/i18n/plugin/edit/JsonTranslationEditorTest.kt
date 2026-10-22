package com.ibrahimdans.i18n.plugin.edit

import com.ibrahimdans.i18n.plugin.ide.settings.KeyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The layout cases are those of the PSI insertion (`JsonInsertionLayoutTest`), on text alone. */
class JsonTranslationEditorTest {

    private val editor = JsonTranslationEditor
    private val sorted = KeyOrder.IGNORE_CASE.comparator

    private fun insert(text: String, key: String, value: String, order: Comparator<String>? = null): String =
        TextEdit.apply(text, editor.insert(text, key.split('.'), value, order))

    private fun rename(text: String, key: String, newName: String): String =
        TextEdit.apply(text, editor.rename(text, key.split('.'), newName))

    private fun delete(text: String, key: String): String = TextEdit.apply(text, editor.delete(text, key.split('.')))

    private fun sort(text: String, order: Comparator<String> = sorted): String = TextEdit.apply(text, editor.sort(text, order))

    @Test
    fun `a two-space file keeps two spaces`() {
        assertEquals("{\n  \"a\": \"A\",\n  \"b\": \"B\"\n}", insert("{\n  \"a\": \"A\"\n}", "b", "B"))
    }

    @Test
    fun `a four-space file keeps four spaces`() {
        assertEquals("{\n    \"a\": \"A\",\n    \"b\": \"B\"\n}", insert("{\n    \"a\": \"A\"\n}", "b", "B"))
    }

    @Test
    fun `a tab-indented file keeps tabs`() {
        val text = insert("{\n\t\"a\": \"A\",\n\t\"n\": {\n\t\t\"x\": \"X\"\n\t}\n}", "n.y", "Y")
        assertEquals("{\n\t\"a\": \"A\",\n\t\"n\": {\n\t\t\"x\": \"X\",\n\t\t\"y\": \"Y\"\n\t},\n\t\"b\": \"B\"\n}", insert(text, "b", "B"))
    }

    @Test
    fun `a one-line object stays on one line`() {
        assertEquals("{ \"a\": \"A\", \"b\": \"B\" }", insert("{ \"a\": \"A\" }", "b", "B"))
    }

    @Test
    fun `a one-line nested object in a four-space file stays on one line`() {
        val text = insert("{\n    \"title\":   \"Titel\",\n    \"nested\": { \"a\": \"A-de\" }\n}", "nested.b", "B-de")
        assertEquals(
            "{\n    \"title\":   \"Titel\",\n    \"nested\": { \"a\": \"A-de\", \"b\": \"B-de\" },\n    \"cancel\": \"Abbrechen\"\n}",
            insert(text, "cancel", "Abbrechen"),
        )
    }

    @Test
    fun `a nested key is created with the file indent`() {
        assertEquals(
            "{\n    \"title\":   \"Titel\",\n    \"save\": {\n        \"label\": {\n            \"short\": \"Save\"\n        }\n    }\n}",
            insert("{\n    \"title\":   \"Titel\"\n}", "save.label.short", "Save"),
        )
    }

    @Test
    fun `a nested key created in a one-line object stays on one line`() {
        assertEquals("{\"a\": \"A\", \"n\": {\"x\": \"X\"}}", insert("{\"a\": \"A\"}", "n.x", "X"))
    }

    @Test
    fun `a key added to a nested object takes its indent`() {
        assertEquals(
            "{\n    \"nested\": {\n        \"a\": \"A\",\n        \"b\": \"B\"\n    }\n}",
            insert("{\n    \"nested\": {\n        \"a\": \"A\"\n    }\n}", "nested.b", "B"),
        )
    }

    @Test
    fun `a sorted insertion in front keeps the file indent`() {
        assertEquals(
            "{\n    \"a\": \"A\",\n    \"b\": \"B\",\n    \"c\": \"C\"\n}",
            insert("{\n    \"b\": \"B\",\n    \"c\": \"C\"\n}", "a", "A", sorted),
        )
    }

    @Test
    fun `a sorted insertion in the middle keeps the file indent`() {
        assertEquals(
            "{\n    \"a\": \"A\",\n    \"b\": \"B\",\n    \"c\": \"C\"\n}",
            insert("{\n    \"a\": \"A\",\n    \"c\": \"C\"\n}", "b", "B", sorted),
        )
    }

    @Test
    fun `an empty object in a four-space file takes the file unit`() {
        assertEquals(
            "{\n    \"a\": \"A\",\n    \"n\": {\n        \"x\": \"X\"\n    }\n}",
            insert("{\n    \"a\": \"A\",\n    \"n\": {}\n}", "n.x", "X"),
        )
    }

    @Test
    fun `an empty file gets the default indent`() {
        assertEquals("{\n  \"a\": \"A\"\n}", insert("{}", "a", "A"))
    }

    @Test
    fun `an empty object in a one-line object stays on one line`() {
        assertEquals("{\"n\": {\"x\": \"X\"}}", insert("{\"n\": {}}", "n.x", "X"))
    }

    @Test
    fun `odd spacing elsewhere is left as it is`() {
        val before = "{\n    \"title\":   \"Titel\",\n\n    \"quoted\" : \"Zitat\",\n    \"n\": {\"x\":\"X\"}\n}"
        assertEquals(
            "{\n    \"title\":   \"Titel\",\n\n    \"quoted\" : \"Zitat\",\n    \"n\": {\"x\":\"X\"},\n    \"z\": \"Z\"\n}",
            insert(before, "z", "Z"),
        )
    }

    @Test
    fun `the value and the key are escaped`() {
        assertEquals("{\"a\": 1, \"say \\\"hi\\\"\": \"line\\nbreak \\\\ \\\"q\\\"\"}", insert("{\"a\": 1}", "say \"hi\"", "line\nbreak \\ \"q\""))
    }

    @Test
    fun `an existing key, a leaf in the way or a broken file give no edit`() {
        assertTrue(editor.insert("{\"a\": \"A\"}", listOf("a"), "B").isEmpty())
        assertTrue(editor.insert("{\"a\": {\"b\": \"B\"}}", listOf("a"), "B").isEmpty())
        assertTrue(editor.insert("{\"a\": \"A\"}", listOf("a", "b"), "B").isEmpty())
        assertTrue(editor.insert("{\"a\": ", listOf("b"), "B").isEmpty())
    }

    @Test
    fun `the sorted insertion counts the schema of a Paraglide file`() {
        assertEquals(
            "{\n  \"\$schema\": \"s\",\n  \"a\": \"A\",\n  \"b\": \"B\"\n}",
            insert("{\n  \"\$schema\": \"s\",\n  \"b\": \"B\"\n}", "a", "A", sorted),
        )
    }

    @Test
    fun `rename keeps the quotes and the value`() {
        assertEquals("{\"menu\": {\"store\": \"Save\", \"b\": 1}}", rename("{\"menu\": {\"save\": \"Save\", \"b\": 1}}", "menu.save", "store"))
        assertTrue(editor.rename("{\"a\": 1}", listOf("b"), "c").isEmpty())
    }

    @Test
    fun `delete takes the comma before, or after for the first entry`() {
        assertEquals("{\"a\": \"1\", \"c\": \"3\"}", delete("{\"a\": \"1\", \"b\": \"2\", \"c\": \"3\"}", "b"))
        assertEquals("{\"b\": \"2\"}", delete("{\"a\": \"1\", \"b\": \"2\"}", "a"))
        assertEquals("{\"a\": \"1\"}", delete("{\"a\": \"1\", \"b\": \"2\"}", "b"))
    }

    @Test
    fun `delete removes the line of a multi-line entry`() {
        val text = "{\n  \"a\": \"1\",\n  \"n\": {\n    \"x\": \"X\",\n    \"y\": \"Y\"\n  },\n  \"c\": \"3\"\n}"
        assertEquals("{\n  \"a\": \"1\",\n  \"c\": \"3\"\n}", delete(text, "n"))
        assertEquals("{\n  \"a\": \"1\",\n  \"n\": {\n    \"y\": \"Y\"\n  },\n  \"c\": \"3\"\n}", delete(text, "n.x"))
        assertEquals("{\n  \"a\": \"1\",\n  \"n\": {\n    \"x\": \"X\"\n  },\n  \"c\": \"3\"\n}", delete(text, "n.y"))
    }

    @Test
    fun `deleting the only entry leaves an empty object`() {
        assertEquals("{\"n\": {}}", delete("{\"n\": {\n  \"x\": \"X\"\n}}", "n.x"))
        assertTrue(editor.delete("{\"a\": 1}", listOf("b")).isEmpty())
    }

    @Test
    fun `sort moves only the entries out of place, recursively`() {
        // The slots keep their whitespace: the four spaces of the first one now precede `a`.
        assertEquals(
            "{\n    \"a\": \"A\",\n  \"b\": {\"x\": 1, \"y\": 2},\n  \"c\":   \"C\"\n}",
            sort("{\n    \"c\":   \"C\",\n  \"b\": {\"y\": 2, \"x\": 1},\n  \"a\": \"A\"\n}"),
        )
    }

    @Test
    fun `sort carries a moved object already sorted`() {
        assertEquals(
            "{\"a\": {\"x\": 1, \"y\": 2}, \"b\": 1}",
            sort("{\"b\": 1, \"a\": {\"y\": 2, \"x\": 1}}"),
        )
    }

    @Test
    fun `sort follows the key order`() {
        val text = "{\"apple\": 1, \"Zebra\": 2, \"banana\": 3, \"Banana\": 4}"
        assertEquals("{\"apple\": 1, \"banana\": 3, \"Banana\": 4, \"Zebra\": 2}", sort(text))
        assertEquals("{\"Banana\": 4, \"Zebra\": 2, \"apple\": 1, \"banana\": 3}", sort(text, KeyOrder.CHARACTER_CODE.comparator))
    }

    @Test
    fun `a sorted file gives no edit`() {
        assertTrue(editor.sort("{\"a\": 1, \"b\": {\"c\": 1, \"d\": 2}}", sorted).isEmpty())
    }
}
