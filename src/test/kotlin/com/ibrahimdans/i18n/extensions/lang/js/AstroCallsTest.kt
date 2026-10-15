package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.elementAt
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.completion.CompletionType
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * `t('key')` inside an `.astro` component (astro-i18next, plain i18next), in its two contexts:
 * the `---` frontmatter, which is TypeScript, and a `{…}` expression of the template.
 *
 * No registration of ours targets Astro. The Astro plugin parses both contexts into JavaScript
 * PSI, so the extensions registered for `language="JavaScript"` (annotator, references,
 * completion) and the JS/TS folding builder apply inside a component without knowing it — as
 * for Svelte (`SvelteHighlightingTest`).
 *
 * The Astro plugin is not bundled in IntelliJ Ultimate: without it a `.astro` file is
 * `PLAIN_TEXT` and none of this works. It is a test dependency only (`build.gradle.kts`), the
 * plugin declares no dependency on it.
 *
 * These cases exist to catch the day that stops being true: if a future Astro plugin changes
 * how it embeds its script, the keys stop resolving and this suite says so.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class AstroCallsTest : PlatformBaseTest() {

    override fun getTestDataPath(): String = "src/test/resources/astro"

    private val config = Config(foldingPreferredLanguage = "en", foldingMaxLength = 20, foldingEnabled = true)

    private fun withTranslations(block: () -> Unit) = myFixture.runWithConfig(config) {
        myFixture.copyFileToProject("assets/en/test.json")
        block()
    }

    private fun frontmatter(call: String) = "---\nimport { t } from 'i18next';\nconst title = $call;\n---\n<h1>{title}</h1>\n"

    private fun template(call: String) = "---\nimport { t } from 'i18next';\n---\n<h1>{$call}</h1>\n"

    /**
     * "No error is reported" is also true of a file nobody parsed: without this, a highlighting
     * case would pass with the Astro plugin absent and prove nothing.
     */
    private fun assertParsedAsAstro() =
        Assertions.assertEquals("Astro", myFixture.file.fileType.name, "the component must be parsed as Astro")

    private fun resolvedValue(source: String): String? {
        myFixture.configureByText("Reference.astro", source)
        assertParsedAsAstro()
        var value: String? = null
        read {
            val element = myFixture.elementAt(myFixture.file, "test:page.title")
            Assertions.assertNotNull(element, "no PSI element carries the key")
            value = element!!.references.firstOrNull()?.resolve()?.text?.unQuote()
        }
        return value
    }

    private fun completes(source: (String) -> String) {
        myFixture.configureByText("Completion.astro", source("t('test:page.ti<caret>')"))
        myFixture.complete(CompletionType.BASIC, 1)
        myFixture.checkResult(source("t('test:page.title')"))
    }

    // ── Annotator: a resolved key is silent, a missing one is reported ───────────

    @Test
    fun `a key in the frontmatter is annotated`() = withTranslations {
        myFixture.configureByFile("frontmatter.astro")
        assertParsedAsAstro()
        myFixture.checkHighlighting(true, false, false, true)
    }

    @Test
    fun `a key in a template expression is annotated`() = withTranslations {
        myFixture.configureByFile("template.astro")
        assertParsedAsAstro()
        myFixture.checkHighlighting(true, false, false, true)
    }

    // ── References: Ctrl+click reaches the value ─────────────────────────────────

    @Test
    fun `a key in the frontmatter resolves`() = withTranslations {
        Assertions.assertEquals("Welcome", resolvedValue(frontmatter("t('test:page.title')")))
    }

    @Test
    fun `a key in a template expression resolves`() = withTranslations {
        Assertions.assertEquals("Welcome", resolvedValue(template("t('test:page.title')")))
    }

    // ── Completion ───────────────────────────────────────────────────────────────

    @Test
    fun `a key in the frontmatter is completed`() = withTranslations { completes(::frontmatter) }

    @Test
    fun `a key in a template expression is completed`() = withTranslations { completes(::template) }

    // ── Folding: the call is replaced by its value ───────────────────────────────

    @Test
    fun `a call in the frontmatter is folded`() = withTranslations {
        myFixture.testFolding("$testDataPath/frontmatterFolding.astro")
    }

    @Test
    fun `a call in a template expression is folded`() = withTranslations {
        myFixture.testFolding("$testDataPath/templateFolding.astro")
    }
}
