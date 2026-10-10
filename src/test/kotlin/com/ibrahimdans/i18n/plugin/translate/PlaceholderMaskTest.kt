package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.translate.PlaceholderMask.Kind
import com.ibrahimdans.i18n.plugin.translate.PlaceholderMask.Rejection
import com.ibrahimdans.i18n.plugin.translate.PlaceholderMask.Unmasked
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * What a translation engine must not touch is hidden behind `<x id="N"/>` and restored afterwards;
 * a translation that loses, duplicates or invents a tag, or breaks ICU or markup, is refused.
 */
class PlaceholderMaskTest {

    private fun roundTrip(text: String) = PlaceholderMask.mask(text).let { PlaceholderMask.unmask(it.text, it) }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Hello {{name}}!",
            "Hello {{ user.name }}, {{count}} new",
            "Hello {name}",
            "Hello %{name}",
            "%s of %d, %1\$s",
            "Click <0>here</0> or <1>there</1>",
            "Some <b>bold</b> and a <br/> break",
            "You have {count, plural, =0 {no item} one {# item} other {# items}}",
            "{gender, select, male {He} female {She} other {They}} replied to {name}",
            "Nested {count, plural, one {{name} has # <b>item</b>} other {{name} has # items}}",
            "No placeholder at all",
            "",
        ]
    )
    fun `every syntax comes back unchanged`(text: String) {
        assertEquals(Unmasked.Restored(text), roundTrip(text))
    }

    @Test
    fun `nothing protected is left in the masked text`() {
        val masked = PlaceholderMask.mask("Hi {{name}}, <0>{count, plural, one {# item} other {# items}}</0> %s")

        listOf("{{name}}", "<0>", "</0>", "plural", "%s", "#").forEach { assertFalse(it in masked.text, "'$it' leaked: ${masked.text}") }
    }

    @Test
    fun `the branch text of an ICU block stays translatable`() {
        val masked = PlaceholderMask.mask("{count, plural, one {# item} other {# items}}")

        assertEquals("""<x id="0"/><x id="1"/><x id="2"/> item<x id="3"/><x id="4"/><x id="5"/> items<x id="6"/><x id="7"/>""", masked.text)
        assertEquals(listOf(Kind.ICU, Kind.ICU, Kind.VARIABLE, Kind.ICU, Kind.ICU, Kind.VARIABLE, Kind.ICU, Kind.ICU), masked.tokens.map { it.kind })
    }

    @Test
    fun `a translation that moves a variable is accepted`() {
        val masked = PlaceholderMask.mask("{{count}} messages from {{name}}")

        assertEquals(
            Unmasked.Restored("Messages de {{name}} : {{count}}"),
            PlaceholderMask.unmask("""Messages de <x id="1"/> : <x id="0"/>""", masked)
        )
    }

    @Test
    fun `a tag written another way by the engine is still read`() {
        val masked = PlaceholderMask.mask("Hello {{name}}")

        assertEquals(Unmasked.Restored("Bonjour {{name}}"), PlaceholderMask.unmask("Bonjour <x id='0' />", masked))
        assertEquals(Unmasked.Restored("Bonjour {{name}}"), PlaceholderMask.unmask("""Bonjour <x id="0"></x>""", masked))
    }

    @Test
    fun `a lost, duplicated or invented tag is refused`() {
        val masked = PlaceholderMask.mask("Hello {{name}}, you have {{count}}")

        assertEquals(Unmasked.Rejected(Rejection.MISSING), PlaceholderMask.unmask("""Bonjour <x id="0"/>""", masked))
        assertEquals(Unmasked.Rejected(Rejection.DUPLICATED), PlaceholderMask.unmask("""<x id="0"/> <x id="0"/> <x id="1"/>""", masked))
        assertEquals(Unmasked.Rejected(Rejection.UNKNOWN), PlaceholderMask.unmask("""<x id="0"/> <x id="1"/> <x id="2"/>""", masked))
    }

    @Test
    fun `ICU pieces moved out of order are refused`() {
        val masked = PlaceholderMask.mask("{count, plural, one {# item} other {# items}}")
        val swapped = masked.text.replace("""<x id="1"/>""", "@").replace("""<x id="4"/>""", """<x id="1"/>""").replace("@", """<x id="4"/>""")

        assertEquals(Unmasked.Rejected(Rejection.ICU_REORDERED), PlaceholderMask.unmask(swapped, masked))
    }

    @Test
    fun `markup that no longer nests is refused`() {
        val masked = PlaceholderMask.mask("<b>bold <i>both</i></b>")

        assertEquals(Unmasked.Rejected(Rejection.MARKUP_BROKEN), PlaceholderMask.unmask("""<x id="0"/>gras <x id="1"/>les deux<x id="3"/><x id="2"/>""", masked))
    }
}
