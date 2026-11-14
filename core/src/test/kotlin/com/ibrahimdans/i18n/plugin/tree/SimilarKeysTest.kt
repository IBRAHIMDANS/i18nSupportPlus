package com.ibrahimdans.i18n.plugin.tree

import com.ibrahimdans.i18n.plugin.format.JsonTranslationTree
import com.ibrahimdans.i18n.plugin.format.ParsedNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SimilarKeysTest {

    private fun key(text: String) = text.split('.')

    private fun closest(unknown: String, vararg keys: String) =
        SimilarKeys.closest(key(unknown), keys.map(::key)).map { it.joinToString(".") }

    @Test
    fun `one character missing, added, changed or two swapped is one edit`() {
        assertEquals(1, SimilarKeys.editDistance("buton", "button", 2))
        assertEquals(1, SimilarKeys.editDistance("buttton", "button", 2))
        assertEquals(1, SimilarKeys.editDistance("bitton", "button", 2))
        assertEquals(1, SimilarKeys.editDistance("butotn", "button", 2))
        assertEquals(0, SimilarKeys.editDistance("same", "same", 0))
    }

    @Test
    fun `a distance beyond the bound is no distance`() {
        assertNull(SimilarKeys.editDistance("kitten", "sitting", 2))
        assertNull(SimilarKeys.editDistance("a", "abcd", 2))
        assertNull(SimilarKeys.editDistance("ab", "ba", 0))
    }

    @Test
    fun `a typo in a segment finds the key`() {
        assertEquals(listOf("button.save"), closest("buton.save", "button.save", "button.cancel", "title"))
    }

    @Test
    fun `a key with no neighbour finds nothing`() {
        assertEquals(emptyList<String>(), closest("profile.avatar", "button.save", "button.cancel"))
    }

    @Test
    fun `short segments must match exactly`() {
        assertEquals(emptyList<String>(), closest("user.ok", "user.id", "user.on"))
    }

    @Test
    fun `a key of another shape is not offered`() {
        assertEquals(emptyList<String>(), closest("save", "button.save"))
    }

    @Test
    fun `at most three keys, the closest first, then in alphabetical order`() {
        assertEquals(
            listOf("menu.items", "menu.itemsx", "menu.item"),
            closest("menu.itemss", "menu.itemxx", "menu.item", "menu.itemsx", "menu.items", "menu.itemz"),
        )
    }

    @Test
    fun `the whole key is allowed two edits`() {
        assertEquals(emptyList<String>(), closest("buton.sav.titl", "button.save.title"))
        assertEquals(listOf("button.save.title"), closest("buton.sav.title", "button.save.title"))
    }

    @Test
    fun `the key itself is never offered`() {
        assertEquals(listOf("button.saves"), closest("button.save", "button.save", "button.saves"))
    }

    @Test
    fun `the keys of a file are its leaves, plural forms read as their base`() {
        val tree: Tree<ParsedNode> = JsonTranslationTree.parse("""{"button": {"save": "Save", "cancel": "Cancel"}, "item_one": "1", "item_other": "n"}""")!!
        assertEquals(
            listOf(listOf("item"), listOf("button", "save"), listOf("button", "cancel")),
            SimilarKeys.keysOf(tree, { error("entries are listed") }, "-"),
        )
    }

    @Test
    fun `the keys read from a file are bounded`() {
        val json = (1..50).joinToString(",", "{", "}") { "\"k$it\": \"v\"" }
        assertEquals(10, SimilarKeys.keysOf<ParsedNode>(JsonTranslationTree.parse(json)!!, { error("entries are listed") }, "-", maxKeys = 10).size)
    }

    @Test
    fun `the namespace written before the key is kept`() {
        assertEquals("common:button.save", SimilarKeys.rewrite("common:buton.save", key("buton.save"), emptyList(), key("button.save"), "."))
    }

    @Test
    fun `the prefix of the hook is not written`() {
        assertEquals("save", SimilarKeys.rewrite("sav", key("form.sav"), key("form"), key("form.save"), "."))
        assertNull(SimilarKeys.rewrite("sav", key("form.sav"), key("form"), key("forms.sav"), "."))
    }

    @Test
    fun `a key not written as it reads is not rewritten`() {
        assertNull(SimilarKeys.rewrite("other", key("buton.save"), emptyList(), key("button.save"), "."))
    }
}
