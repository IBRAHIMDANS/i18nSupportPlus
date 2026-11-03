package com.ibrahimdans.i18n.extract.angular

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AngularMessageIdsTest {

    private fun ids(messages: List<AngularMessageIds.Message>) = messages.map { it.id }

    @Test
    fun `a template's markers give their custom id, a marker without one gives none`() {
        val template = """
            <h1 i18n="@@home.title">Welcome</h1>
            <p i18n="Shown under the title|Introduction@@home.intro">Everything</p>
            <img i18n-alt="@@home.logo.alt" alt="Logo" src="logo.png">
            <footer i18n="Footer of every page">Made with care</footer>
            <span i18n>Bare</span>
            <p i18n='@@single.quoted'>x</p>
        """.trimIndent()

        assertEquals(
            listOf("home.title", "home.intro", "home.logo.alt", null, null, "single.quoted"),
            ids(AngularMessageIds.ofTemplate(template)),
        )
    }

    @Test
    fun `comments, text and pipes are no markers`() {
        val template = """
            <!-- <p i18n="@@commented">x</p> -->
            <p>The i18n="@@text" attribute is documented here</p>
            <p>{{ count | i18nPlural: map }}</p>
        """.trimIndent()

        assertEquals(emptyList<String?>(), ids(AngularMessageIds.ofTemplate(template)))
    }

    @Test
    fun `the offset is the marker's, so a line can be told`() {
        val template = "<div>\n  <h1 i18n=\"@@home.title\">Welcome</h1>\n</div>"
        val offset = AngularMessageIds.ofTemplate(template).single().offset
        assertEquals(2, template.substring(0, offset).count { it == '\n' } + 1)
    }

    @Test
    fun `a script's localize templates and inline template markers are read`() {
        val script = """
            @Component({ selector: 'app', template: '<h2 i18n="@@inline.title">Hi</h2>' })
            export class App {
              saved = ${'$'}localize`:@@cart.saved:Your cart was saved`;
              greeting = ${'$'}localize`:Greeting|Shown in the header@@home.greeting:Hello ${'$'}{this.name}:name:!`;
              anonymous = ${'$'}localize`Thank you`;
              described = ${'$'}localize`:Only a meaning|and a description:Text`;
              i18n = 'not a marker';
            }
        """.trimIndent()

        assertEquals(
            listOf("inline.title", "cart.saved", "home.greeting", null, null),
            ids(AngularMessageIds.ofScript(script)),
        )
    }

    @Test
    fun `the ids ng extract-i18n computes are told from written ones`() {
        assertTrue(AngularMessageIds.isComputedId("6040034932389633522"))
        assertTrue(AngularMessageIds.isComputedId("a".repeat(40)))
        assertFalse(AngularMessageIds.isComputedId("home.title"))
        assertFalse(AngularMessageIds.isComputedId("introductionHeader"))
    }
}
