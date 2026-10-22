package com.ibrahimdans.i18n.plugin.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PseudoLocalizerTest {

    @Test
    fun `letters are accented, the text bracketed and lengthened`() {
        // 8 letters: 3 padding dots (30 %, rounded up).
        assertEquals("[Šéţţîñĝš ···]", PseudoLocalizer.localize("Settings"))
    }

    @Test
    fun `an empty or blank value is left as is`() {
        assertEquals("", PseudoLocalizer.localize(""))
        assertEquals("  ", PseudoLocalizer.localize("  "))
    }

    @Test
    fun `variables of every syntax are kept`() {
        val pseudo = PseudoLocalizer.localize("Hi {{name}}, {{- raw}} %{count} {user} %s %1\$s {amount, number}")
        listOf("{{name}}", "{{- raw}}", "%{count}", "{user}", "%s", "%1\$s", "{amount, number}").forEach {
            assertTrue(it in pseudo, "$it lost in $pseudo")
        }
    }

    @Test
    fun `tags and entities are kept`() {
        val pseudo = PseudoLocalizer.localize("See <1>the terms</1><br/>&nbsp;<a href='/x'>link</a>")
        listOf("<1>", "</1>", "<br/>", "&nbsp;", "<a href='/x'>", "</a>").forEach {
            assertTrue(it in pseudo, "$it lost in $pseudo")
        }
        assertTrue("ţĥé ţéŕɱš" in pseudo, pseudo)
    }

    @Test
    fun `an ICU block keeps its syntax and accents its branches`() {
        assertEquals(
            "[{count, plural, one {# îţéɱ} other {# îţéɱš}} ···]",
            PseudoLocalizer.localize("{count, plural, one {# item} other {# items}}")
        )
    }

    @Test
    fun `the same message always gives the same result`() {
        val message = "Hello {{name}}, <b>welcome</b>"
        assertEquals(PseudoLocalizer.localize(message), PseudoLocalizer.localize(message))
    }
}
