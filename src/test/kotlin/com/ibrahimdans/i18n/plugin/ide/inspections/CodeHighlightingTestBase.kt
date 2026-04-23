package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.CodeTranslationGenerators
import com.ibrahimdans.i18n.plugin.ide.JsCodeAndTranslationGeneratorsNs
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.CodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.code.TsxCodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.JsonTranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.TranslationGenerator
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource

fun CodeInsightTestFixture.customHighlightingCheck(fileName: String, code: String, translationName: String, translation: String) = this.runWithConfig(Config(defaultNs = "translation")) {
    val parent = translationName.substringBeforeLast("/", "")
    if (parent.isNotEmpty()) this.tempDirFixture.findOrCreateDir(parent)
    this.addFileToProject(translationName, translation)
    this.configureByText(fileName, code)
    this.checkHighlighting(true, true, true, true)
}

class CodeHighlightingTestBase: PlatformBaseTest() {

    @Test
    fun notInContext() = myFixture.customHighlightingCheck(
        "notInContext.js",
            "function test() { \"don't try to resolve this text!\" }",
            "test.json",
        "root: {}"
    )

    @ParameterizedTest
    @ArgumentsSource(CodeTranslationGenerators::class)
    fun testReferenceToObject(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.customHighlightingCheck(
        "refToObject.${cg.ext()}",
        cg.generate("\"test:<error descr=\"Reference to object\">tst2.plurals</error>\""),
        "test.${tg.ext()}",
        tg.generatePlural("tst2", "plurals", "value", "value1", "value2", "value5")
    )

    @ParameterizedTest
    @ArgumentsSource(CodeTranslationGenerators::class)
    fun testReferenceToObjectDefaultNs(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.customHighlightingCheck(
        "refToObjectDefNs.${cg.ext()}",
        cg.generate("\"<error descr=\"Reference to object\">tst2.plurals</error>\""),
        "assets/translation.${tg.ext()}",
        tg.generatePlural("tst2", "plurals", "value", "value1", "value2", "value5")
    )

    @ParameterizedTest
    @ArgumentsSource(CodeTranslationGenerators::class)
    fun testResolved(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.customHighlightingCheck(
        "resolved.${cg.ext()}",
        cg.generate("\"test:tst1.base.single\""),
        "assets/translation.${tg.ext()}",
        tg.generatePlural("tst2", "plurals", "value", "value1", "value2", "value5")
    )

    @ParameterizedTest
    @ArgumentsSource(CodeTranslationGenerators::class)
    fun testNotArg(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.customHighlightingCheck(
        "defNsUnresolved.${cg.ext()}",
        cg.generateInvalid(
            "\"test:tst1.base5.single\""
        ),
        "assets/test.${tg.ext()}",
        tg.generatePlural("tst2", "plurals", "value", "value1", "value2", "value5")
    )

    @ParameterizedTest
    @ArgumentsSource(CodeTranslationGenerators::class)
    fun testExpressionInsideTranslation(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.customHighlightingCheck(
        "expressionInTranslation.${cg.ext()}",
        cg.generate("isSelected ? \"test:<error descr=\"Reference to object\">tst2.plurals</error>\" : \"test:<error descr=\"Unresolved key\">unresolved.whole.key</error>\""),
        "test.${tg.ext()}",
        tg.generatePlural("tst2", "plurals", "value", "value1", "value2", "value5")
    )

    @Test
    fun testPartiallyTranslatedInspection() = myFixture.runWithConfig(Config(partialTranslationInspectionEnabled = true)) {
        val cg = TsxCodeGenerator()
        val tg = JsonTranslationGenerator()
        myFixture.addFileToProject("en/test.json",
            tg.generateNamedBlock(
                "root",
                tg.generateNamedBlock(
                    "sub",
                    tg.generateNamedBlock("base", "\"Partially defined translation\""),
                    1
                )
            )
        )
        myFixture.addFileToProject("ru/test.json",
            tg.generateNamedBlock(
                "root",
                    tg.generateNamedBlock(
                    "another", "\"value\""
                )
            )
        )
        myFixture.configureByText("sample.tsx", cg.generate("'test:root.<error descr=\"Partially translated key\">sub.base</error>'"))
        myFixture.checkHighlighting(true, true, true, true)
    }

    /**
     * Bug 1 regression: t('dashboard:stats.users.count') with useTranslation() (no namespace)
     * must resolve via the namespace in the key prefix and produce no annotation error.
     */
    @Test
    fun testExplicitNamespaceKeyWithUseTranslationNoAnnotation() {
        val tg = JsonTranslationGenerator()
        myFixture.addFileToProject("en/dashboard.${tg.ext()}", """{"stats":{"users":{"count":"42 users"}}}""")
        myFixture.configureByText("Navigation.tsx", """
            import { useTranslation } from 'react-i18next';
            export default function Navigation() {
                const { t } = useTranslation();
                return t('dashboard:stats.users.count');
            }
        """.trimIndent())
        myFixture.checkHighlighting(true, true, true, true)
    }

    @ParameterizedTest
    @ArgumentsSource(JsCodeAndTranslationGeneratorsNs::class)
    fun testDefNsUnresolved(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.customHighlightingCheck(
            "defNsUnresolved.${cg.ext()}",
            cg.multiGenerate(
                    "\"<error descr=\"Missing default translation file\">missing.default.translation</error>\"",
                    "`<error descr=\"Missing default translation file\">missing.default.in.\${template}</error>`"
            ),
            "assets/test.${tg.ext()}",
            tg.generatePlural("tst2", "plurals", "value", "value1", "value2", "value5")
    )

    @ParameterizedTest
    @ArgumentsSource(JsCodeAndTranslationGeneratorsNs::class)
    fun testUnresolvedKey(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.customHighlightingCheck(
            "unresolvedKey.${cg.ext()}",
            cg.multiGenerate(
                    "\"test:tst1.<error descr=\"Unresolved key\">unresolved.part.of.key</error>\"",
                    "\"test:<error descr=\"Unresolved key\">unresolved.whole.key</error>\"",
                    "`test:tst1.<error descr=\"Unresolved key\">unresolved.part.of.key.\${arg}</error>`",
                    "`test:<error descr=\"Unresolved key\">unresolved.whole.\${arg}</error>`",
                    "`test:<error descr=\"Unresolved key\">unresolved.whole.\${arg}</error>`",
                    "`test:<error descr=\"Unresolved key\">unresolved.whole.\${b ? 'key' : 'key2'}</error>`",
                    "`test:tst1.<error descr=\"Unresolved key\">unresolved.part.of.\${b ? 'key' : 'key2'}</error>`"
            ),
            "test.${tg.ext()}",
            tg.generateContent("tst1", "base", "single", "only one value")
    )

    @ParameterizedTest
    @ArgumentsSource(JsCodeAndTranslationGeneratorsNs::class)
    fun testUnresolvedNs(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.customHighlightingCheck(
            "unresolvdNs.${cg.ext()}",
            cg.multiGenerate(
                    "\"<error descr=\"Unresolved namespace\">unresolved</error>:tst1.base\"",
                    "`<error descr=\"Unresolved namespace\">unresolved</error>:tst1.base.\${arg}`"
            ),
            "test.${tg.ext()}",
            tg.generateContent("root", "first", "key", "value")
    )

    @ParameterizedTest
    @ArgumentsSource(JsCodeAndTranslationGeneratorsNs::class)
    fun testResolvedTemplate(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.customHighlightingCheck(
            "resolvedTemplate.${cg.ext()}",
            cg.generate("`test:tst1.base.\${arg}`"),
            "assets/translation.${tg.ext()}",
            tg.generateContent("tst1", "base", "value", "translation")
    )
}

