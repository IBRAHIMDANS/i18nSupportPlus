package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Keys inside a `.svelte` single-file component.
 *
 * No registration of ours targets Svelte. What makes this work is that the Svelte plugin parses
 * a `<script>` block, and `{…}` in the markup, into ordinary JavaScript PSI — so the extensions
 * already registered for `language="JavaScript"` apply inside a component without knowing it.
 *
 * The one thing that was missing is the plugin itself: it is not bundled in IntelliJ Ultimate,
 * and without it a `.svelte` file is `PLAIN_TEXT` — one `PsiPlainTextImpl` and nothing to read.
 * That is why the README described these components as not analysed.
 *
 * These cases exist to catch the day that stops being true: if a future platform changes how
 * Svelte embeds its script, the keys stop resolving and this suite says so.
 */
class SvelteHighlightingTest : PlatformBaseTest() {

    private val translations = """{"menu":{"home":"Home"}}"""

    @Test
    fun resolvesAKeyInsideTheScriptBlock() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText(
            "Ok.svelte",
            """
            <script>
              import { _ } from 'svelte-i18n';
              const label = ${'$'}_('menu.home');
            </script>
            """.trimIndent()
        )
        // Asserted explicitly: "no error is reported" is also true of a file nobody parsed, so
        // without this the case would pass with the Svelte plugin absent and prove nothing.
        Assertions.assertEquals("Svelte", myFixture.file.fileType.name, "the component must be parsed as Svelte")
        myFixture.checkHighlighting(true, false, false, true)
    }

    @Test
    fun reportsAnUnresolvedKeyInsideTheScriptBlock() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText(
            "Missing.svelte",
            """
            <script>
              import { _ } from 'svelte-i18n';
              const label = ${'$'}_('menu.<error descr="Unresolved key">missing</error>');
            </script>
            """.trimIndent()
        )
        myFixture.checkHighlighting(true, false, false, true)
    }

    @Test
    fun reportsAnUnresolvedKeyInTheMarkup() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        // `{…}` in the template is embedded JavaScript too, so it is covered by the same path.
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText(
            "Markup.svelte",
            """
            <script>
              import { _ } from 'svelte-i18n';
            </script>
            <p>{${'$'}_('menu.<error descr="Unresolved key">missing</error>')}</p>
            """.trimIndent()
        )
        myFixture.checkHighlighting(true, false, false, true)
    }
}
