package com.ibrahimdans.i18n.extensions.localization.yaml

import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.JsCodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.YamlTranslationGenerator
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.junit.jupiter.api.Test

private fun CodeInsightTestFixture.customCheck(fileName: String, code: String, translationName: String, translation: String) = this.runWithConfig(
    Config(defaultNs = "translation")
) {
    this.addFileToProject(translationName, translation)
    this.configureByText(fileName, code)
    this.checkHighlighting(true, true, true, true)
}

class CodeHighlightingTest2: PlatformBaseTest() {
    val cg = JsCodeGenerator()
    val tg = YamlTranslationGenerator()

    @Test
    fun testReferenceToObject() = myFixture.customCheck(
            "refToObject.${cg.ext()}",
            cg.generate("\"test:<error descr=\"Reference to object\">tst2.plurals</error>\""),
            "test.${tg.ext()}",
            tg.generatePlural("tst2", "plurals", "value", "value1", "value2", "value5")
    )

    @Test
    fun testExpressionInsideTranslation() = myFixture.customCheck(
            "expressionInTranslation.${cg.ext()}",
            cg.generate("isSelected ? \"test:<error descr=\"Reference to object\">tst2.plurals</error>\" : \"test:<error descr=\"Unresolved key\">unresolved.whole.key</error>\""),
            "test.${tg.ext()}",
            tg.generatePlural("tst2", "plurals", "value", "value1", "value2", "value5")
    )

    @Test
    fun testResolved() = myFixture.customCheck(
            "resolved.${cg.ext()}",
            cg.generate("\"test:tst1.base.single\""),
            "assets/translation.${tg.ext()}",
            tg.generatePlural("tst2", "plurals", "value", "value1", "value2", "value5")
    )

    @Test
    fun testReferenceToObjectDefaultNs() = myFixture.customCheck(
            "refToObjectDefNs.${cg.ext()}",
            cg.generate("\"<error descr=\"Reference to object\">tst2.plurals</error>\""),
            "assets/translation.${tg.ext()}",
            tg.generatePlural("tst2", "plurals", "value", "value1", "value2", "value5")
    )

    @Test
    fun testNotArg() = myFixture.customCheck(
            "defNsUnresolved.${cg.ext()}",
            cg.generateInvalid(
                    "\"test:tst1.base5.single\""
            ),
            "assets/test.${tg.ext()}",
            tg.generatePlural("tst2", "plurals", "value", "value1", "value2", "value5")
    )

}