package com.ibrahimdans.i18n.extensions.localization.yaml

import com.ibrahimdans.i18n.extensions.localization.yaml.YamlScalars.Style
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class YamlScalarsTest {

    @ParameterizedTest
    @ValueSource(strings = ["Save", "Hello world", "Où êtes-vous ?", "a:b", "C#", "100%", "Hi, {name}!", "x-y"])
    fun `a value a plain scalar reads back as is written plain`(value: String) {
        assertEquals(value, YamlScalars.spell(value))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Note: soon", "Save # now", "Ends with:", "", " padded", "padded ",
            "{{count}}", "{{count}} items", "[draft]", "*bold*", "&anchor", "!tag", "@user", "%d files", "`code`", "| pipe", "> quote",
            "- item", "? what", "#hash", "\"quoted\"",
            "yes", "No", "on", "OFF", "true", "null", "~", "y", "123", "-4", "1.5", "1e3", "0x1F", ".inf", "2026-10-15", "12:30",
        ]
    )
    fun `a value a plain scalar would misread is single-quoted`(value: String) {
        assertEquals("'$value'", YamlScalars.spell(value))
    }

    @Test
    fun `a single quote is doubled inside single quotes`() {
        assertEquals("'It''s: done'", YamlScalars.spell("It's: done"))
        assertEquals("'it''s'", YamlScalars.spell("it's", Style.SINGLE))
    }

    @Test
    fun `line breaks and control characters take double quotes and escapes`() {
        assertEquals("\"one\\ntwo\"", YamlScalars.spell("one\ntwo"))
        assertEquals("\"a\\tb \\\"c\\\" \\\\ d\"", YamlScalars.spell("a\tb \"c\" \\ d"))
        assertEquals("\"bell\\x07\"", YamlScalars.spell("bell\u0007"))
        assertEquals("\"one\\ntwo\"", YamlScalars.spell("one\ntwo", Style.SINGLE))
    }

    @Test
    fun `the preferred quotes are kept when they can carry the value`() {
        assertEquals("'Save'", YamlScalars.spell("Save", Style.SINGLE))
        assertEquals("\"Save\"", YamlScalars.spell("Save", Style.DOUBLE))
        assertEquals("'Note: soon'", YamlScalars.spell("Note: soon", Style.PLAIN))
    }

    @Test
    fun `the style is read from the scalar's first character`() {
        assertEquals(Style.SINGLE, YamlScalars.styleOf("'x'"))
        assertEquals(Style.DOUBLE, YamlScalars.styleOf("\"x\""))
        assertEquals(Style.PLAIN, YamlScalars.styleOf("x"))
        assertEquals(Style.PLAIN, YamlScalars.styleOf("|\n  x"))
        assertEquals(Style.PLAIN, YamlScalars.styleOf(""))
    }
}
