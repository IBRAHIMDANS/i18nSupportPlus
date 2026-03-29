package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.generator.code.PhpGetTextCodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.PoTranslationGenerator

class PhpGettextHighlightingTest : PlatformBaseTest() {

    private val cg = PhpGetTextCodeGenerator("gettext")
    private val tg = PoTranslationGenerator()

    private fun check(fileName: String, code: String, translationName: String, translation: String) {
        myFixture.addFileToProject(translationName, translation)
        myFixture.configureByText(fileName, code)
        myFixture.checkHighlighting(true, true, true, true)
    }

    // GNU GetText plugin (org.jetbrains.plugins.localization) is not available for IntelliJ 243.x builds.
    // Without it, PlainObjectLocalization is not registered and .po files are not resolved,
    // so the annotation becomes "Missing default translation file" instead of "Unresolved key".
    // Renamed to skip: JUnit3-style TestCase ignores method-level @Ignore, only convention `test*` matters.
    fun ignoredTestUnresolved() {
        check(
            "defNsUnresolved.${cg.ext()}",
            cg.multiGenerate(
                "\"<error descr=\"Unresolved key\">missing.default.translation</error>\""
            ),
            "en-US/LC_MESSAGES/none.${tg.ext()}",
            tg.generatePlural("tst2", "plurals", "value", "value1", "value2", "value5")
        )
    }

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

    fun ignoredTestMissingTranslationFile() {
        check(
            "code.${cg.ext()}",
            cg.generate("\"<error descr=\"Missing default translation file\">unresolved.whole.key</error>\""),
            "en-US/INVALID_FOLDER/translation.${tg.ext()}",
            tg.generatePlural("tst2", "plurals", "value", "value1", "value2", "value5")
        )
    }
}
