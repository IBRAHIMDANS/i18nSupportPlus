package com.ibrahimdans.i18n.plugin.ide.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import javax.swing.JTextField

class LocaleChoiceTest {

    @Test
    fun `the combo edits through the field it is given`() {
        val field = JTextField("en")
        val combo = LocaleChoice.combo(field)

        assertSame(field, combo.editor.editorComponent)
        assertEquals("en", field.text)
    }

    @Test
    fun `picking a locale writes the field`() {
        val field = JTextField("en")
        val combo = LocaleChoice.combo(field)
        LocaleChoice.offer(combo, listOf("de", "fr"))

        combo.selectedItem = "fr"

        assertEquals("fr", field.text)
    }

    @Test
    fun `offering the locales keeps the value set, listed or not`() {
        val field = JTextField("pt-BR")
        val combo = LocaleChoice.combo(field)

        LocaleChoice.offer(combo, listOf("de", "fr", "fr"))

        assertEquals("pt-BR", field.text)
        assertEquals(listOf("de", "fr"), (0 until combo.itemCount).map { combo.getItemAt(it) })
    }

    @Test
    fun `an empty selection leaves the field as it is`() {
        val field = JTextField("en")
        val combo = LocaleChoice.combo(field)

        combo.selectedItem = null

        assertEquals("en", field.text)
    }

    @Test
    fun `a locale typed in the field is what the combo holds`() {
        val field = JTextField("en")
        val combo = LocaleChoice.combo(field)

        field.text = "uk"

        assertEquals("uk", combo.editor.item)
    }
}
