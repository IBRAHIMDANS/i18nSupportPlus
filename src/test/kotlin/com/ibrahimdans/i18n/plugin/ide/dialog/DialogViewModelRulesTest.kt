package com.ibrahimdans.i18n.plugin.ide.dialog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rules the translation dialog checks the key and the message variables against.
 *
 * They live in [DialogViewModel]'s companion object precisely so they can be pinned here: the
 * listeners that call them are widget callbacks and cannot be exercised headlessly, and an
 * unpinned rule is how "the key already exists" used to be discovered after the save rather
 * than before it. Same path [TranslationDialogNamespaceTest] follows for the namespace rule.
 */
class DialogViewModelRulesTest {

    // -----------------------------------------------------------------------
    // Key check
    // -----------------------------------------------------------------------

    @Test
    fun `an empty key is neither valid nor invalid, only empty`() {
        listOf("", "   ").forEach {
            assertEquals(KeyCheck.EMPTY, DialogViewModel.checkKey(it, ".", emptySet()))
        }
    }

    @Test
    fun `a key nobody has taken is available`() {
        assertEquals(
            KeyCheck.AVAILABLE,
            DialogViewModel.checkKey("menu.home", ".", setOf("menu.about"))
        )
    }

    @Test
    fun `a key already defined in the namespace is taken`() {
        assertEquals(
            KeyCheck.TAKEN,
            DialogViewModel.checkKey("  menu.home  ", ".", setOf("menu.home"))
        )
    }

    @Test
    fun `an empty segment is invalid`() {
        // "a..b", ".b" and "a." all name a level with no name.
        listOf("menu..home", ".home", "menu.").forEach {
            assertEquals(
                KeyCheck.INVALID_SEGMENT,
                DialogViewModel.checkKey(it, ".", emptySet()),
                "expected '$it' to be rejected"
            )
        }
    }

    @Test
    fun `a segment containing a space is invalid`() {
        assertEquals(
            KeyCheck.INVALID_SEGMENT,
            DialogViewModel.checkKey("menu.home page", ".", emptySet())
        )
    }

    @Test
    fun `a flat-key project has one segment, dots included`() {
        // No separator: "app.header.title" is one JSON property, so the dots are just characters.
        assertEquals(
            KeyCheck.AVAILABLE,
            DialogViewModel.checkKey("app.header.title", "", emptySet())
        )
        assertEquals(
            KeyCheck.TAKEN,
            DialogViewModel.checkKey("app.header.title", "", setOf("app.header.title"))
        )
        assertEquals(
            KeyCheck.INVALID_SEGMENT,
            DialogViewModel.checkKey("app.header title", "", emptySet())
        )
    }

    // -----------------------------------------------------------------------
    // Message variables
    // -----------------------------------------------------------------------

    @Test
    fun `reads the three variable shapes the plugin supports`() {
        assertEquals(setOf("{{count}}"), DialogViewModel.messageVariables("You have {{count}} messages"))
        assertEquals(setOf("{name}"), DialogViewModel.messageVariables("Hello {name}"))
        assertEquals(setOf("%s", "%1\$s"), DialogViewModel.messageVariables("%s and %1\$s"))
    }

    @Test
    fun `a double-brace variable is not read as a single-brace one`() {
        // The greedy single-brace alternative would report "{count}", which nobody typed.
        assertEquals(setOf("{{count}}"), DialogViewModel.messageVariables("{{count}}"))
    }

    @Test
    fun `spacing inside a variable is not a difference`() {
        assertEquals(
            DialogViewModel.messageVariables("{{count}}"),
            DialogViewModel.messageVariables("{{ count }}")
        )
    }

    @Test
    fun `ranges point at the variables in place`() {
        val text = "Hi {name}, {{count}} new"
        val ranges = DialogViewModel.variableRanges(text)
        assertEquals(listOf("{name}", "{{count}}"), ranges.map { text.substring(it.first, it.last + 1) })
    }

    @Test
    fun `flags the locale that drops a variable the others carry`() {
        val missing = DialogViewModel.missingVariables(
            mapOf(
                "en" to "You have {{count}} messages",
                "fr" to "Vous avez des messages",
                "de" to "Sie haben {{count}} Nachrichten"
            )
        )
        assertEquals(setOf("fr"), missing.keys)
        assertEquals(setOf("{{count}}"), missing["fr"])
    }

    @Test
    fun `an untranslated locale has lost nothing`() {
        val missing = DialogViewModel.missingVariables(
            mapOf("en" to "You have {{count}} messages", "fr" to "   ")
        )
        assertTrue(missing.isEmpty(), "a blank value is not a translation that lost a variable")
    }

    @Test
    fun `nothing to compare means nothing to report`() {
        // One filled value has no other value to differ from, and a message without variables
        // cannot lose one.
        assertTrue(DialogViewModel.missingVariables(mapOf("en" to "Hello {name}")).isEmpty())
        assertTrue(DialogViewModel.missingVariables(mapOf("en" to "Hello", "fr" to "Bonjour")).isEmpty())
    }

    // -----------------------------------------------------------------------
    // Most complete locale
    // -----------------------------------------------------------------------

    @Test
    fun `the most complete locale is the one translated for the most keys`() {
        val translations = mapOf(
            "menu.home" to mapOf("en" to "Home", "fr" to "Accueil"),
            "menu.about" to mapOf("en" to "About", "fr" to ""),
            "menu.help" to mapOf("en" to "Help")
        )
        assertEquals("en", DialogViewModel.mostCompleteLocale(translations, setOf("en", "fr")))
    }

    @Test
    fun `ties are broken by locale name so the choice does not move between openings`() {
        val translations = mapOf(
            "menu.home" to mapOf("en" to "Home", "de" to "Startseite")
        )
        assertEquals("de", DialogViewModel.mostCompleteLocale(translations, setOf("en", "de")))
    }

    @Test
    fun `no candidate, no locale`() {
        assertNull(DialogViewModel.mostCompleteLocale(emptyMap(), emptySet()))
    }

    // -----------------------------------------------------------------------
    // Donor locale
    // -----------------------------------------------------------------------

    /** `fr` is the fullest here, so every fallback below lands on it. */
    private val translations = mapOf(
        "menu.home" to mapOf("en" to "Home", "fr" to "Accueil"),
        "menu.about" to mapOf("fr" to "À propos")
    )

    @Test
    fun `a declared reference locale wins over the fullest one`() {
        assertEquals(
            "en",
            DialogViewModel.donorLocale(listOf("en"), translations, setOf("en", "fr")),
            "the module says translators work from en, however thin en still is"
        )
    }

    @Test
    fun `an undeclared reference falls back to the fullest locale`() {
        assertEquals("fr", DialogViewModel.donorLocale(listOf(""), translations, setOf("en", "fr")))
        assertEquals("fr", DialogViewModel.donorLocale(emptyList(), translations, setOf("en", "fr")))
    }

    @Test
    fun `a reference the dialog is not showing is no reference at all`() {
        assertEquals(
            "fr",
            DialogViewModel.donorLocale(listOf("de"), translations, setOf("en", "fr")),
            "de belongs to another module, or its file is gone: the button must still work"
        )
    }

    @Test
    fun `the first module declaring a usable reference decides`() {
        assertEquals(
            "en",
            DialogViewModel.donorLocale(listOf("", "de", "en", "fr"), translations, setOf("en", "fr"))
        )
    }
}
