package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.TsCodeGenerator
import org.junit.jupiter.api.Test

/**
 * Highlighting tests for the `flatKeys` setting.
 *
 * react-intl / FormatJS projects usually store ids as flat properties: `"app.header.title"`
 * is a single JSON key, not three nested levels. With the default nested resolution the
 * whole file is unresolvable; `flatKeys` looks the key up as one literal instead.
 */
class FlatKeyHighlightingTest : PlatformBaseTest() {

    private val cg = TsCodeGenerator()

    private val flatTranslations = """{"app.header.title": "Titre", "app.header.subtitle": "Sous-titre"}"""
    private val nestedTranslations = """{"app": {"header": {"title": "Titre"}}}"""

    @Test
    fun testFlatKeyResolvedAgainstFlatFile() = myFixture.runWithConfig(Config(defaultNs = "translation", flatKeys = true)) {
        addFileToProject("assets/translation.json", flatTranslations)
        myFixture.configureByText("test.${cg.ext()}", cg.generate("\"app.header.title\""))
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testMissingFlatKeyIsReported() = myFixture.runWithConfig(Config(defaultNs = "translation", flatKeys = true)) {
        addFileToProject("assets/translation.json", flatTranslations)
        myFixture.configureByText(
            "test.${cg.ext()}",
            cg.generate("\"<error descr=\"Unresolved key\">app.header.missing</error>\"")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    /**
     * Baseline the setting exists for: with nested resolution a flat file resolves nothing,
     * because `app` is looked up as a property and the file has no such property.
     */
    @Test
    fun testFlatFileIsUnresolvableWithoutTheSetting() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", flatTranslations)
        myFixture.configureByText(
            "test.${cg.ext()}",
            cg.generate("\"<error descr=\"Unresolved key\">app.header.title</error>\"")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    /**
     * Symmetric regression guard: turning the setting on must not silently keep resolving
     * nested files — a nested file no longer matches a dotted key.
     */
    @Test
    fun testNestedFileIsUnresolvableWithTheSetting() = myFixture.runWithConfig(Config(defaultNs = "translation", flatKeys = true)) {
        addFileToProject("assets/translation.json", nestedTranslations)
        myFixture.configureByText(
            "test.${cg.ext()}",
            cg.generate("\"<error descr=\"Unresolved key\">app.header.title</error>\"")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    /**
     * With flat keys there is no namespace parsing left: a colon is just part of the key,
     * and the file is still located through the default namespace. Same behaviour as gettext.
     */
    @Test
    fun testNamespaceSeparatorIsPartOfTheKey() = myFixture.runWithConfig(Config(defaultNs = "translation", flatKeys = true)) {
        addFileToProject("assets/translation.json", """{"common:app.title": "Titre"}""")
        myFixture.configureByText("test.${cg.ext()}", cg.generate("\"common:app.title\""))
        myFixture.checkHighlighting(true, true, true, true)
    }

    /**
     * The default (nested) behaviour must be untouched for every existing project.
     */
    @Test
    fun testNestedResolutionStillWorksByDefault() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", nestedTranslations)
        myFixture.configureByText("test.${cg.ext()}", cg.generate("\"app.header.title\""))
        myFixture.checkHighlighting(true, true, true, true)
    }
}
