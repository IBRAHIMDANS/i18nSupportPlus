package com.ibrahimdans.i18n.plugin.translate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TranslationMemoryTest {

    private val known = mapOf(
        "common:actions.save" to mapOf("en" to "Save", "fr" to "Enregistrer"),
        "common:actions.cancel" to mapOf("en" to "Cancel", "fr" to ""),
        "common:greeting" to mapOf("en" to "Hello {{name}}", "fr" to "Bonjour {{name}}"),
        "common:broken" to mapOf("en" to "Bye {{name}}", "fr" to "Au revoir"),
        "cart:empty" to mapOf("en" to "Your cart is empty", "fr" to "Votre panier est vide"),
    )

    private fun memory(known: Map<String, Map<String, String>> = this.known, excluded: Set<String> = emptySet()) =
        TranslationMemory.of(known, "en", "fr", excluded)

    @Test
    fun `a text another key translates gets that translation`() {
        assertEquals(TranslationMemory.Match("Enregistrer", unanimous = true, keys = 1), memory().lookup("Save"))
    }

    @Test
    fun `whitespace and the first letter's case are ignored, the first letter following the text`() {
        assertEquals("Votre panier est vide", memory().lookup("  Your   cart is\nempty ")?.value)
        assertEquals("enregistrer", memory().lookup("save")?.value, "a lower-case text gets a lower-case translation")
        assertNull(memory().lookup("SAVE"), "only the first letter's case is ignored")
    }

    @Test
    fun `a translation carrying other placeholders is not offered`() {
        assertEquals("Bonjour {{name}}", memory().lookup("Hello {{name}}")?.value)
        assertNull(memory().lookup("Bye {{name}}"), "the existing value lost {{name}}")
        assertNull(memory().lookup("Hello {{user}}"), "another placeholder is another text")
    }

    @Test
    fun `a blank value, an unknown text and an excluded key give nothing`() {
        assertNull(memory().lookup("Cancel"), "the French value is blank")
        assertNull(memory().lookup("Delete"))
        assertNull(memory().lookup("  "))
        assertNull(memory(excluded = setOf("common:actions.save")).lookup("Save"), "a value being replaced is no example")
    }

    @Test
    fun `divergent translations give the most frequent one, not unanimous`() {
        val divergent = mapOf(
            "a:save" to mapOf("en" to "Save", "fr" to "Sauvegarder"),
            "b:save" to mapOf("en" to "Save", "fr" to "Enregistrer"),
            "c:save" to mapOf("en" to "save", "fr" to "enregistrer"),
        )

        assertEquals(TranslationMemory.Match("Enregistrer", unanimous = false, keys = 2), memory(divergent).lookup("Save"))
    }

    @Test
    fun `a tie goes to the first key in order`() {
        val tie = mapOf(
            "b:save" to mapOf("en" to "Save", "fr" to "Enregistrer"),
            "a:save" to mapOf("en" to "Save", "fr" to "Sauvegarder"),
        )

        assertEquals(TranslationMemory.Match("Sauvegarder", unanimous = false, keys = 1), memory(tie).lookup("Save"))
    }

    @Test
    fun `placeholders in another order still match`() {
        val known = mapOf("a:range" to mapOf("en" to "From {{min}} to {{max}}", "fr" to "Jusqu'à {{max}} depuis {{min}}"))

        assertEquals("Jusqu'à {{max}} depuis {{min}}", memory(known).lookup("From {{min}} to {{max}}")?.value)
    }
}
