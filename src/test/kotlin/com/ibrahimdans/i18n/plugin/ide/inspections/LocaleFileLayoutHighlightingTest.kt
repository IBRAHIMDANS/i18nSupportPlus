package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.TsCodeGenerator
import org.junit.jupiter.api.Test

/**
 * End-to-end highlighting for the "one file per locale" layout: `locales/fr.json` +
 * `locales/en.json`, keys carrying no namespace.
 *
 * Every consumer — annotator, completion, folding, hints, inlay hints, references, quickfix —
 * goes through `findSources(fullKey.allNamespaces(), …)`, which found nothing for such a
 * project and reported the whole file as having no translation file at all.
 */
class LocaleFileLayoutHighlightingTest : PlatformBaseTest() {

    private val cg = TsCodeGenerator()

    private fun addLocaleFiles() {
        addFileToProject("locales/fr.json", """{"dashboard": {"title": "Ma pharmacie"}}""")
        addFileToProject("locales/en.json", """{"dashboard": {"title": "My pharmacy"}}""")
    }

    @Test
    fun keyWithoutNamespaceResolvesAgainstLocaleFiles() = myFixture.runWithConfig(Config()) {
        addLocaleFiles()
        myFixture.configureByText("test.${cg.ext()}", cg.generate("\"dashboard.title\""))
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun missingKeyIsStillReportedOnThatLayout() = myFixture.runWithConfig(Config()) {
        addLocaleFiles()
        myFixture.configureByText(
            "test.${cg.ext()}",
            cg.generate("\"dashboard.<error descr=\"Unresolved key\">subtitle</error>\"")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    /**
     * The boundary of the fallback: an explicit namespace matching no file is a configuration
     * error, and must not be papered over with the locale files that happen to be around.
     */
    @Test
    fun explicitNamespaceStaysUnresolved() = myFixture.runWithConfig(Config()) {
        addLocaleFiles()
        myFixture.configureByText(
            "test.${cg.ext()}",
            cg.generate("\"<error descr=\"Unresolved namespace\">common</error>:user.name\"")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    /** With no translation file at all, the key is still reported as having none. */
    @Test
    fun aProjectWithoutAnyTranslationFileIsStillReported() = myFixture.runWithConfig(Config()) {
        myFixture.configureByText(
            "test.${cg.ext()}",
            cg.generate("\"<error descr=\"Missing default translation file\">dashboard.title</error>\"")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }
}
