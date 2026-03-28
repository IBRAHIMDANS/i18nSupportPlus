package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.PhpCodeAndTranslationGenerators
import com.ibrahimdans.i18n.plugin.utils.generator.code.CodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.TranslationGenerator
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource

class PhpHighlightingTestBase: PlatformBaseTest() {

    @Test
    fun testNotInContext() = myFixture.customHighlightingCheck(
            "notInContext.php",
            "function test() { \"don't try to resolve this text!\" }",
            "test.json",
            "root: {}"
    )

    @ParameterizedTest
    @ArgumentsSource(PhpCodeAndTranslationGenerators::class)
    fun testDefNsUnresolved(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.customHighlightingCheck(
        "defNsUnresolved.${cg.ext()}",
        cg.multiGenerate(
                "\"<error descr=\"Missing default translation file\">missing.default.translation</error>\"",
                "'<error descr=\"Missing default translation file\">missing.default.in.translation</error>'"
        ),
        "assets/test.${tg.ext()}",
        tg.generatePlural("tst2", "plurals", "value", "value1", "value2", "value5")
    )

    @ParameterizedTest
    @ArgumentsSource(PhpCodeAndTranslationGenerators::class)
    fun testUnresolvedKey(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.customHighlightingCheck(
        "unresolvedKey.${cg.ext()}",
        cg.multiGenerate(
            "\"test:tst1.<error descr=\"Unresolved key\">unresolved.part.of.key</error>\"",
            "\"test:<error descr=\"Unresolved key\">unresolved.whole.key</error>\"",
            "'test:tst1.<error descr=\"Unresolved key\">unresolved.part.of.key</error>'",
            "'test:<error descr=\"Unresolved key\">unresolved.whole.key</error>'"
        ),
        "test.${tg.ext()}",
        tg.generateContent("tst1", "base", "single", "only one value")
    )

    @ParameterizedTest
    @ArgumentsSource(PhpCodeAndTranslationGenerators::class)
    fun testUnresolvedNs(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.customHighlightingCheck(
        "unresolvdNs.${cg.ext()}",
        cg.multiGenerate(
            "\"<error descr=\"Unresolved namespace\">unresolved</error>:tst1.base\"",
            "'<error descr=\"Unresolved namespace\">unresolved</error>:tst1.base'"
        ),
        "test.${tg.ext()}",
        tg.generateContent("root", "first", "key", "value")
    )
}