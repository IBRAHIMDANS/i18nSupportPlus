package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.generator.code.TsxCodeGenerator
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The *Partially translated key* warning and regional locales: `en-GB` inherits the keys of `en`
 * (#767), so a key `en` holds is translated in `en-GB` too.
 */
class RegionalPartialTranslationTest : PlatformBaseTest() {

    private fun partialWarningOn(key: String): Boolean {
        myFixture.configureByText("sample.tsx", TsxCodeGenerator().generate("'$key'"))
        return PluginBundle.getMessage("annotator.partially.translated") in myFixture.doHighlighting().mapNotNull { it.description }
    }

    private fun addFiles() {
        myFixture.addFileToProject("en/test.json", """{"root": {"color": "Color", "save": "Save"}}""")
        myFixture.addFileToProject("en-GB/test.json", """{"root": {"color": "Colour"}}""")
        myFixture.addFileToProject("fr/test.json", """{"root": {"color": "Couleur", "save": "Enregistrer"}}""")
    }

    @Test
    fun aKeyARegionalLocaleInheritsIsNotPartiallyTranslated() =
        myFixture.runWithConfig(Config(partialTranslationInspectionEnabled = true)) {
            addFiles()
            assertFalse(partialWarningOn("test:root.save"), "en-GB inherits root.save from en")
        }

    @Test
    fun withRegionalFallbackOffTheRegionalLocaleMakesItPartial() =
        myFixture.runWithConfig(Config(partialTranslationInspectionEnabled = true, regionalFallback = false)) {
            addFiles()
            assertTrue(partialWarningOn("test:root.save"))
        }

    @Test
    fun aKeyItsLanguageLacksTooIsStillPartial() =
        myFixture.runWithConfig(Config(partialTranslationInspectionEnabled = true)) {
            myFixture.addFileToProject("en/test.json", """{"root": {"color": "Color"}}""")
            myFixture.addFileToProject("en-GB/test.json", """{"root": {"color": "Colour"}}""")
            myFixture.addFileToProject("fr/test.json", """{"root": {"color": "Couleur", "save": "Enregistrer"}}""")
            assertTrue(partialWarningOn("test:root.save"), "neither en nor en-GB holds root.save")
        }
}
