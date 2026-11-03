package com.ibrahimdans.i18n.extensions.localization.xliff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The placeholders written for the interpolations of a value, compared with what `ng extract-i18n` writes for
 * the same template text (Angular 17, `--format xlf` and `--format xlf2`).
 */
class XliffPlaceholdersTest {

    private fun xliff12(value: String) = XliffContentGenerator.placeholdersXml(value, version2 = false)

    private fun xliff2(value: String) = XliffContentGenerator.placeholdersXml(value, version2 = true)

    @Test
    fun `one interpolation in XLIFF 1_2`() {
        // <p i18n="@@greet">Hello {{ user.name }}!</p>
        assertEquals("""Hello <x id="INTERPOLATION" equiv-text="{{ user.name }}"/>!""", xliff12("Hello {{ user.name }}!"))
    }

    @Test
    fun `two interpolations are numbered from the second one`() {
        // <p i18n="@@cart.count">{{ count }} items for {{name}}</p>
        assertEquals(
            """<x id="INTERPOLATION" equiv-text="{{ count }}"/> items for <x id="INTERPOLATION_1" equiv-text="{{name}}"/>""",
            xliff12("{{ count }} items for {{name}}"),
        )
    }

    @Test
    fun `the same expression reuses its name, the same written otherwise does not`() {
        assertEquals(
            """<x id="INTERPOLATION" equiv-text="{{ a }}"/>, <x id="INTERPOLATION" equiv-text="{{ a }}"/>, <x id="INTERPOLATION_1" equiv-text="{{a}}"/>""",
            xliff12("{{ a }}, {{ a }}, {{a}}"),
        )
    }

    @Test
    fun `XLIFF 2 numbers each placeholder and names it in equiv`() {
        assertEquals(
            """Hi <ph id="0" equiv="INTERPOLATION" disp="{{ name }}"/>, <ph id="1" equiv="INTERPOLATION_1" disp="{{ count }}"/> new, <ph id="2" equiv="INTERPOLATION" disp="{{ name }}"/>""",
            xliff2("Hi {{ name }}, {{ count }} new, {{ name }}"),
        )
    }

    @Test
    fun `the text around the placeholders is escaped`() {
        assertEquals("""Tom &amp; <x id="INTERPOLATION" equiv-text="{{ name }}"/> &lt;3""", xliff12("Tom & {{ name }} <3"))
    }

    @Test
    fun `a value without interpolation is left to plain escaping`() {
        assertNull(xliff12("Save changes"))
    }

    @Test
    fun `a value holding inline elements already is never rewritten`() {
        assertNull(xliff12("""Hello <x id="INTERPOLATION" equiv-text="{{ name }}"/>!"""))
        assertNull(xliff2("""Hello <ph id="0" equiv="INTERPOLATION" disp="{{ name }}"/>!"""))
    }
}
