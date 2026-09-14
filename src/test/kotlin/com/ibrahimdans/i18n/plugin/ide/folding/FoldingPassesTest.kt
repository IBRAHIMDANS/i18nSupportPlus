package com.ibrahimdans.i18n.plugin.ide.folding

import com.ibrahimdans.i18n.extensions.lang.js.JsFoldingBuilder
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.JsCodeGenerator
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Folding regions across repeated passes and file layouts, which the fixture-driven
 * [FoldingTestBase] cannot express: it runs the builders once, on a `locale/ns` layout.
 */
internal class FoldingPassesTest : PlatformBaseTest() {

    private val config = Config(foldingPreferredLanguage = "en", foldingMaxLength = 20, foldingEnabled = true)

    private fun placeholders(): List<String> = ReadAction.compute<List<String>, RuntimeException> {
        JsFoldingBuilder()
            .buildFoldRegions(myFixture.file, myFixture.editor.document, false)
            .map { it.placeholderText.orEmpty() }
    }

    /**
     * The daemon replays folding without an edit — toggling folding on restarts it. A set shared
     * across calls and invalidated only on edit left that pass with every range already claimed.
     */
    @Test
    fun secondPassOnUnchangedDocumentKeepsItsRegions() = myFixture.runWithConfig(config) {
        addFileToProject("assets/en/test.json", """{"ref": {"title": "Hello"}}""")
        myFixture.configureByText("again.js", JsCodeGenerator().generate("\"test:ref.title\"", 0))

        assertEquals(listOf("Hello"), placeholders())
        assertEquals(listOf("Hello"), placeholders(), "An unchanged document must keep its folding")
    }

    /** `locales/en.json`: the locale is the file name, the parent directory is `locales`. */
    @Test
    fun oneFilePerLocaleLayoutIsFolded() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en.json", """{"home": {"title": "Welcome"}}""")
        addFileToProject("locales/fr.json", """{"home": {"title": "Bienvenue"}}""")
        myFixture.configureByText("flat.js", JsCodeGenerator().generate("\"home.title\"", 0))

        assertEquals(listOf("Welcome"), placeholders())
    }
}
