package com.ibrahimdans.i18n.plugin.edit

import com.ibrahimdans.i18n.plugin.ide.settings.KeyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YamlTranslationEditorTest {

    private val editor = YamlTranslationEditor()
    private val sorted = KeyOrder.IGNORE_CASE.comparator

    private fun insert(text: String, key: String, value: String, order: Comparator<String>? = null, editor: TranslationEditor = this.editor): String =
        TextEdit.apply(text, editor.insert(text, key.split('.'), value, order))

    private fun rename(text: String, key: String, newName: String): String =
        TextEdit.apply(text, editor.rename(text, key.split('.'), newName))

    private fun delete(text: String, key: String): String = TextEdit.apply(text, editor.delete(text, key.split('.')))

    private fun sort(text: String): String = TextEdit.apply(text, editor.sort(text, sorted))

    @Test
    fun `a key is appended at the column of its mapping`() {
        assertEquals("a: A\nb: B\n", insert("a: A\n", "b", "B"))
        assertEquals("menu:\n    a: A\n    b: B\nz: Z\n", insert("menu:\n    a: A\nz: Z\n", "menu.b", "B"))
    }

    @Test
    fun `a comment closing the last line stays on it`() {
        assertEquals("ref:\n  key: Ref #\n  b: B", insert("ref:\n  key: Ref #", "ref.b", "B"))
    }

    @Test
    fun `a nested key is indented by the configured indent`() {
        // The case of `GeneratedIndentTest`, with a 4-space indent setting.
        assertEquals("x: y\na:\n    b:\n        c: v\n", insert("x: y\n", "a.b.c", "v", editor = YamlTranslationEditor("    ")))
        assertEquals("menu:\n  a: A\n  save:\n    label: Save\n", insert("menu:\n  a: A\n", "menu.save.label", "Save"))
    }

    @Test
    fun `a sorted insertion goes in front or in the middle`() {
        assertEquals("menu:\n  a: A\n  b: B\n  c: C\n", insert("menu:\n  b: B\n  c: C\n", "menu.a", "A", sorted))
        assertEquals("a: A\nb: B\nc: C\n", insert("a: A\nc: C\n", "b", "B", sorted))
    }

    @Test
    fun `an entry follows the nested mapping it comes after`() {
        assertEquals("a:\n  x: X\n  y: Y\nb: B\n", insert("a:\n  x: X\n  y: Y\n", "b", "B"))
    }

    @Test
    fun `the value is spelled to read back as the same string`() {
        assertEquals("a: A\nb: 'Note: soon'\nc: 'yes'\nd: \"two\\nlines\"\n",
            insert(insert(insert("a: A\n", "b", "Note: soon"), "c", "yes"), "d", "two\nlines"))
    }

    @Test
    fun `an empty document gets its first key`() {
        assertEquals("a:\n  b: B", insert("", "a.b", "B"))
        assertEquals("# comment\na: A", insert("# comment\n", "a", "A"))
        assertEquals("# comment\na: A", insert("# comment", "a", "A"))
    }

    @Test
    fun `no edit where the key exists, a leaf is in the way or the mapping is a flow one`() {
        assertTrue(editor.insert("a: A\n", listOf("a"), "B").isEmpty())
        assertTrue(editor.insert("a: A\n", listOf("a", "b"), "B").isEmpty())
        assertTrue(editor.insert("a: {x: X}\n", listOf("a", "y"), "Y").isEmpty())
        assertTrue(editor.insert("just a scalar\n", listOf("a"), "A").isEmpty())
        assertTrue(editor.insert("a: [\n", listOf("b"), "B").isEmpty())
    }

    @Test
    fun `rename keeps the quotes the key is written in`() {
        assertEquals("button:\n  store: Save\n", rename("button:\n  save: Save\n", "button.save", "store"))
        assertEquals("'b': Q\n", rename("'a': Q\n", "a", "b"))
        assertEquals("\"b\": Q\n", rename("\"a\": Q\n", "a", "b"))
    }

    @Test
    fun `delete removes the lines of the entry, nested ones and comments included`() {
        val text = "a: A # first\nmenu:\n  x: X\n  y: Y\nz: Z\n"
        assertEquals("menu:\n  x: X\n  y: Y\nz: Z\n", delete(text, "a"))
        assertEquals("a: A # first\nz: Z\n", delete(text, "menu"))
        assertEquals("a: A # first\nmenu:\n  y: Y\nz: Z\n", delete(text, "menu.x"))
        assertEquals("a: A # first\nmenu:\n  x: X\n  y: Y\n", delete(text, "z"))
        assertEquals("a: A\n", delete("a: A\nb: B", "b"))
    }

    @Test
    fun `delete in a flow mapping takes its comma`() {
        assertEquals("a: {x: X}\n", delete("a: {x: X, y: Y}\n", "a.y"))
        assertEquals("a: {y: Y}\n", delete("a: {x: X, y: Y}\n", "a.x"))
        assertTrue(editor.delete("a: A\n", listOf("b")).isEmpty())
    }

    @Test
    fun `sort swaps entries in their slots, nested mappings moving whole`() {
        assertEquals(
            "a: A\nmenu:\n  x: X\n  y: Y\nz: Z\n",
            sort("z: Z\nmenu:\n  y: Y\n  x: X\na: A\n"),
        )
        assertEquals("a:\n  b: B\nc: C\n", sort("c: C\na:\n  b: B\n"))
    }

    @Test
    fun `sort keeps comments between entries in their slot`() {
        assertEquals("a: A\n# between\nb: B\n", sort("b: B\n# between\na: A\n"))
    }

    @Test
    fun `sort orders a flow mapping too`() {
        assertEquals("o: {a: 1, b: 2}\n", sort("o: {b: 2, a: 1}\n"))
    }
}
