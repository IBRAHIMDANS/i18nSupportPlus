package com.ibrahimdans.i18n.plugin.check

import com.ibrahimdans.i18n.plugin.translate.Glossary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlossaryCheckTest {

    private val glossary = Glossary(
        listOf(
            Glossary.Term("Acme"),
            Glossary.Term("cart", mapOf("fr" to "panier", "de" to "Warenkorb")),
            Glossary.Term("checkout", mapOf("de" to "Kasse"))
        )
    )

    private fun breaches(reference: String, value: String, locale: String = "fr") =
        GlossaryCheck.breaches(glossary, reference, value, locale)

    @Test
    fun `a kept term missing from the translation is reported`() {
        assertEquals(listOf(GlossaryBreach("Acme", "Acme", kept = true)), breaches("Welcome to Acme", "Bienvenue chez Akmé"))
        assertTrue(breaches("Welcome to Acme", "Bienvenue chez Acme").isEmpty())
    }

    @Test
    fun `the translation the glossary gives is respected`() {
        assertTrue(breaches("Your cart is empty", "Votre panier est vide").isEmpty())
        assertTrue(breaches("Your cart is empty", "Ihr Warenkorb ist leer", "de").isEmpty())
    }

    @Test
    fun `another translation of the term is reported`() {
        assertEquals(listOf(GlossaryBreach("cart", "panier", kept = false)), breaches("Your cart is empty", "Votre chariot est vide"))
    }

    @Test
    fun `a term the reference does not hold asks nothing`() {
        assertTrue(breaches("Your basket is empty", "Votre chariot est vide").isEmpty())
        // `carton` holds `cart`, not as a word.
        assertTrue(breaches("A carton box", "Une boîte").isEmpty())
    }

    @Test
    fun `a term without a translation for the locale asks nothing`() {
        assertTrue(breaches("Go to checkout", "Passer à la caisse").isEmpty())
        assertEquals(listOf(GlossaryBreach("checkout", "Kasse", kept = false)), breaches("Go to checkout", "Zur Bezahlung", "de"))
    }

    @Test
    fun `terms are matched as whole words ignoring case`() {
        assertTrue(breaches("Cart", "Panier").isEmpty())
        assertEquals(1, breaches("CART", "paniers").size, "an inflected form is not recognised")
        assertTrue(GlossaryCheck.contains("Hello, ACME!", "Acme"))
        assertFalse(GlossaryCheck.contains("Acmes", "Acme"))
    }

    @Test
    fun `a regional locale takes the language's translation`() {
        assertTrue(breaches("Your cart", "Votre panier", "fr-CA").isEmpty())
    }

    @Test
    fun `an empty glossary reports nothing`() {
        assertTrue(GlossaryCheck.breaches(Glossary.EMPTY, "Welcome to Acme", "Bienvenue", "fr").isEmpty())
    }
}
