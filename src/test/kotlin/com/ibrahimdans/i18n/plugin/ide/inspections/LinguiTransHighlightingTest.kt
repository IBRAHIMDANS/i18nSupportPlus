package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.LinguiTransSourceGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.PoTranslationGenerator
import org.junit.jupiter.api.Test

/**
 * Highlighting tests for Lingui <Trans>source text</Trans> source-based approach.
 *
 * Tests that use .po files are prefixed `ignoredTest` because the GNU GetText plugin
 * (org.jetbrains.plugins.localization) is not available for IntelliJ 243.x builds.
 * They will run once the plugin is bundled again.
 */
class LinguiTransHighlightingTest : PlatformBaseTest() {

    private val cg = LinguiTransSourceGenerator()

    @Test
    fun testMissingTranslationFile() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        myFixture.configureByText(
            "test.${cg.ext()}",
            cg.generate("""<error descr="Missing default translation file">Hello world!</error>""")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testUnresolvedTransKey() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        myFixture.addFileToProject("assets/translation.json", """{"other": "value"}""")
        myFixture.configureByText(
            "test.${cg.ext()}",
            cg.generate("""<error descr="Unresolved key">Hello world!</error>""")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testResolvedTransKeyJson() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        myFixture.addFileToProject("assets/translation.json", """{"Hello world!": "Bonjour monde!"}""")
        myFixture.configureByText("test.${cg.ext()}", cg.generate("Hello world!"))
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testNotInTransContext() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        // Text inside a plain <div> must NOT trigger extraction
        myFixture.addFileToProject("assets/translation.json", """{"Hello world!": "Bonjour monde!"}""")
        myFixture.configureByText("test.${cg.ext()}", cg.generateInvalid("Hello world!"))
        myFixture.checkHighlighting(true, true, true, true)
    }

    // GNU GetText plugin (org.jetbrains.plugins.localization) is not available for IntelliJ 243.x builds.
    // These methods intentionally lack @Test so they are skipped by the JUnit 5 runner.
    fun ignoredTestResolvedFromPoFile() = myFixture.runWithConfig(Config(defaultNs = "messages")) {
        val tg = PoTranslationGenerator()
        myFixture.addFileToProject(
            "en-US/LC_MESSAGES/messages.${tg.ext()}",
            tg.generateContent("Hello world!", "Bonjour monde!")
        )
        myFixture.configureByText("test.${cg.ext()}", cg.generate("Hello world!"))
        myFixture.checkHighlighting(true, true, true, true)
    }

    fun ignoredTestUnresolvedKeyInPoFile() = myFixture.runWithConfig(Config(defaultNs = "messages")) {
        val tg = PoTranslationGenerator()
        myFixture.addFileToProject(
            "en-US/LC_MESSAGES/messages.${tg.ext()}",
            tg.generateContent("Other key", "Autre clé")
        )
        myFixture.configureByText(
            "test.${cg.ext()}",
            cg.generate("""<error descr="Unresolved key">Hello world!</error>""")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }
}
