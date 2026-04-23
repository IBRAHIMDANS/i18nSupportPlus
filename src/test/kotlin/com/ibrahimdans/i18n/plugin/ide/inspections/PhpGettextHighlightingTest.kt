package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.PhpGetTextCodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.PoTranslationGenerator
import org.junit.jupiter.api.Test

class PhpGettextHighlightingTest : PlatformBaseTest() {

    private val cg = PhpGetTextCodeGenerator("gettext")
    private val tg = PoTranslationGenerator()

    private fun check(fileName: String, code: String, translationName: String, translation: String) {
        addFileToProject(translationName, translation)
        myFixture.configureByText(fileName, code)
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testUnresolved() = myFixture.runWithConfig(Config(gettext = true)) {
        check(
            "defNsUnresolved.${cg.ext()}",
            cg.multiGenerate(
                "\"<error descr=\"Unresolved key\">missing.default.translation</error>\""
            ),
            "en-US/LC_MESSAGES/none.${tg.ext()}",
            tg.generatePlural("tst2", "plurals", "value", "value1", "value2", "value5")
        )
    }

    @Test
    fun testNotArg() {
        check(
            "defNsUnresolved.${cg.ext()}",
            cg.generateInvalid(
                "\"tst1.base5.single\""
            ),
            "assets/en-US.${tg.ext()}",
            tg.generatePlural("tst2", "plurals", "value", "value1", "value2", "value5")
        )
    }

    @Test
    fun testMissingTranslationFile() = myFixture.runWithConfig(Config(gettext = true)) {
        myFixture.configureByText(
            "code.${cg.ext()}",
            cg.generate("\"<error descr=\"Missing default translation file\">unresolved.whole.key</error>\"")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }
}
