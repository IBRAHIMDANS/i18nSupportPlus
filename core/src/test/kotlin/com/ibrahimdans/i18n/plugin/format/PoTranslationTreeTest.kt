package com.ibrahimdans.i18n.plugin.format

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PoTranslationTreeTest {

    private fun values(text: String): List<Pair<String, String?>> =
        PoTranslationTree.parse(text)!!.entries().map { (key, node) -> key to node.value().value }

    @Test
    fun `msgid is the key, msgstr the value, the header no key`() {
        val text = """
            msgid ""
            msgstr "Language: fr\n"

            #: src/App.tsx:12
            #, fuzzy
            msgid "Hello"
            msgstr "Bonjour"
            msgid "Bye"
            msgstr "Salut"
        """.trimIndent()
        assertEquals(listOf("Hello" to "Bonjour", "Bye" to "Salut"), values(text))
    }

    @Test
    fun `continuation lines are joined and escapes decoded`() {
        val text = "msgid \"\"\n\"Two \"\n\"lines\"\nmsgstr \"Deux\\nlignes \\\"ici\\\"\""
        assertEquals(listOf("Two lines" to "Deux\nlignes \"ici\""), values(text))
    }

    @Test
    fun `msgctxt makes another key, an obsolete entry none`() {
        val text = """
            msgctxt "menu"
            msgid "Open"
            msgstr "Ouvrir"

            msgid "Open"
            msgstr "Ouvre"

            #~ msgid "Old"
            #~ msgstr "Vieux"
        """.trimIndent()
        assertEquals(listOf("Open (msgctxt: menu)" to "Ouvrir", "Open" to "Ouvre"), values(text))
    }

    @Test
    fun `a plural entry is one key, empty when any form is`() {
        val text = """
            msgid "one item"
            msgid_plural "many items"
            msgstr[0] "un élément"
            msgstr[1] "des éléments"

            msgid "one file"
            msgid_plural "many files"
            msgstr[0] "un fichier"
            msgstr[1] ""
        """.trimIndent()
        assertEquals(listOf("one item" to "un élément\ndes éléments", "one file" to ""), values(text))
    }

    @Test
    fun `text that is no catalogue gives no tree`() {
        assertNull(PoTranslationTree.parse("{\"hello\": \"world\"}"))
        assertNull(PoTranslationTree.parse("msgid Hello"))
    }
}
