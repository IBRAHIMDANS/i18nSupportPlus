package com.ibrahimdans.i18n.plugin.ide.completion

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.*
import com.ibrahimdans.i18n.plugin.utils.generator.translation.JsonTranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.TranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.YamlTranslationGenerator
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

interface Checker {
    fun doCheck(sourceName: String, sourceCode: String, expectedCode: String, ext: String, translationContent: String)
}

interface KeyGenerator {
    fun generate(ns: String, compositeKey: String, quote: String = "'"): String
}

class DefaultNsKeyGenerator: KeyGenerator {
    override fun generate(ns: String, compositeKey: String, quote: String): String = "$quote$compositeKey$quote"
}

class NsKeyGenerator: KeyGenerator {
    override fun generate(ns: String, compositeKey: String, quote: String): String = "$quote$ns:$compositeKey$quote"
}

internal class BasicChecker(private val fixture: CodeInsightTestFixture) {
    fun doCheck(sourceName: String, sourceCode: String, expectedCode: String, translationName: String, translationContent: String) {
        fixture.addFileToProject(translationName, translationContent)
        fixture.configureByText(sourceName, sourceCode)
        fixture.complete(CompletionType.BASIC, 1)
        fixture.checkResult(expectedCode)
    }
}

internal class DefaultNsChecker(fixture: CodeInsightTestFixture): Checker {
    private val checker = BasicChecker(fixture)
    override fun doCheck(sourceName: String, sourceCode: String, expectedCode: String, ext: String, translationContent: String) {
        checker.doCheck(
            sourceName, sourceCode, expectedCode, "assets/translation.$ext", translationContent
        )
    }
}

internal class NsChecker(fixture: CodeInsightTestFixture): Checker {
    private val checker = BasicChecker(fixture)
    override fun doCheck(sourceName: String, sourceCode: String, expectedCode: String, ext: String, translationContent: String) {
        checker.doCheck(
            sourceName, sourceCode, expectedCode, "assets/test.$ext", translationContent
        )
    }
}

abstract class CodeCompletionTestBase(
    protected val codeGenerator: CodeGenerator,
    protected val translationGenerator: TranslationGenerator,
    protected val keyGenerator: KeyGenerator = NsKeyGenerator(),
    protected val checkerProducer: (fixture: CodeInsightTestFixture) -> Checker = ::NsChecker) : PlatformBaseTest() {

    protected lateinit var checker: Checker

    override fun setUp() {
        super.setUp()
        checker = checkerProducer(myFixture)
    }

    //No completion happens
    @Test
    fun testNoCompletion() = checker.doCheck(
        "none.${codeGenerator.ext()}",
        codeGenerator.generate(keyGenerator.generate("test", "none.base.<caret>")),
        codeGenerator.generate(keyGenerator.generate("test", "none.base.")),
        translationGenerator.ext(),
        translationGenerator.generateContent("tst1", "base", "single", "only one value")
    )

    //Simple case - one possible completion of key: 'test:tst1.base.<caret>'
    @Test
    fun testSingle() {
        checker.doCheck(
            "single.${codeGenerator.ext()}",
            codeGenerator.generate(keyGenerator.generate("test", "tst1.base.<caret>")),
            codeGenerator.generate(keyGenerator.generate("test","tst1.base.single")),
            translationGenerator.ext(),
            translationGenerator.generateContent("tst1", "base", "single", "only one value")
        )
    }

    //Completion of plural key: 'test:tst2.plurals.<caret>'
    @Test
    fun testPlural() {
        checker.doCheck(
            "plural.${codeGenerator.ext()}",
            codeGenerator.generate(keyGenerator.generate("test", "tst2.plurals.<caret>")),
            codeGenerator.generate(keyGenerator.generate("test", "tst2.plurals.value")),
            translationGenerator.ext(),
            translationGenerator.generatePlural("tst2", "plurals", "value", "tt", "qq", "vv")
        )
    }

    //Completion of partially typed key: 'test:tst1.base.si<caret>'
    @Test
    fun testPartial() {
        checker.doCheck(
            "partial.${codeGenerator.ext()}",
            codeGenerator.generate(keyGenerator.generate("test", "tst1.base.si<caret>")),
            codeGenerator.generate(keyGenerator.generate("test","tst1.base.single")),
            translationGenerator.ext(),
            translationGenerator.generateContent("tst1", "base", "single", "only one value")
        )
    }

    @Test
    fun testInvalidCompletion() = checker.doCheck(
        "partial.${codeGenerator.ext()}",
        codeGenerator.generate(keyGenerator.generate("test", "tst1.base.si<caret>ng")),
        codeGenerator.generate(keyGenerator.generate("test","tst1.base.sing")),
        translationGenerator.ext(),
        translationGenerator.generateContent("tst1", "base", "single", "only one value")
    )

}

internal class CodeCompletionTsJsonTest: CodeCompletionTestBase(TsCodeGenerator(), JsonTranslationGenerator(), NsKeyGenerator())
internal class CodeCompletionJsJsonTest: CodeCompletionTestBase(JsCodeGenerator(), JsonTranslationGenerator(), NsKeyGenerator())
internal class CodeCompletionTsxJsonTest: CodeCompletionTestBase(TsxCodeGenerator(), JsonTranslationGenerator(), NsKeyGenerator())
internal class CodeCompletionJsxJsonTest: CodeCompletionTestBase(JsxCodeGenerator(), JsonTranslationGenerator(), NsKeyGenerator())
internal class CodeCompletionTsYamlTest: CodeCompletionTestBase(TsCodeGenerator(), YamlTranslationGenerator(), NsKeyGenerator())
internal class CodeCompletionJsYamlTest: CodeCompletionTestBase(JsCodeGenerator(), YamlTranslationGenerator(), NsKeyGenerator())
internal class CodeCompletionTsxYamlTest: CodeCompletionTestBase(TsxCodeGenerator(), YamlTranslationGenerator(), NsKeyGenerator())
internal class CodeCompletionJsxYamlTest: CodeCompletionTestBase(JsxCodeGenerator(), YamlTranslationGenerator(), NsKeyGenerator())

/**
 * Each completion entry shows its translation in the preview locale and can be found by it:
 * keys used to be listed bare, so one picked a key blind, while one has the text in mind.
 */
internal class CodeCompletionValuesTest : PlatformBaseTest() {

    private val codeGenerator = JsCodeGenerator()

    private fun addMenus() {
        addFileToProject("locales/en/menu.json", """{"menu": {"home": "Home", "help": "Help"}}""")
        addFileToProject("locales/fr/menu.json", """{"menu": {"home": "Accueil", "help": "Aide", "frOnly": "Seulement en français"}}""")
    }

    /** The type text of every offered key, by lookup string. */
    private fun complete(key: String): Map<String, String?> {
        myFixture.configureByText("menu.js", codeGenerator.generate(key))
        myFixture.complete(CompletionType.BASIC, 1)
        return myFixture.lookupElements.orEmpty().associate { element ->
            element.lookupString to LookupElementPresentation.renderElement(element).typeText
        }
    }

    @Test
    fun testTheValueInThePreviewLocaleIsShown() = myFixture.runWithConfig(Config(previewLocale = "fr")) {
        addMenus()
        val offered = complete("'menu:menu.<caret>'")
        assertEquals("Accueil", offered["menu:menu.home"])
        assertEquals("Aide", offered["menu:menu.help"])
    }

    /** Without a preview locale, completion follows the folding language, as hints do — and `en` finds `en-GB`. */
    @Test
    fun testALanguageSettingFindsItsRegionalVariant() = myFixture.runWithConfig(Config(foldingPreferredLanguage = "en")) {
        addFileToProject("locales/en-GB/menu.json", """{"menu": {"colour": "Colour", "help": "Help"}}""")
        val offered = complete("'menu:menu.<caret>'")
        assertEquals("Colour", offered["menu:menu.colour"])
    }

    @Test
    fun testAKeyIsFoundByItsTranslation() = myFixture.runWithConfig(Config(previewLocale = "fr")) {
        addMenus()
        myFixture.configureByText("menu.js", codeGenerator.generate("'menu:menu.acc<caret>'"))
        myFixture.complete(CompletionType.BASIC, 1)
        myFixture.checkResult(codeGenerator.generate("'menu:menu.home'"))
    }

    @Test
    fun testAKeyFromTheRootIsFoundByItsTranslation() = myFixture.runWithConfig(Config(previewLocale = "fr")) {
        addFileToProject("locales/fr/translation.json", """{"home": "Accueil", "help": "Aide"}""")
        myFixture.configureByText("menu.js", codeGenerator.generate("'acc<caret>'"))
        myFixture.complete(CompletionType.BASIC, 1)
        // The lookup also carries the platform's word completions (`accent`, `access`…).
        assertTrue("home" in myFixture.lookupElementStrings.orEmpty(), myFixture.lookupElementStrings.toString())
    }

    /** A key the preview locale lacks shows nothing rather than another locale's text. */
    @Test
    fun testAKeyWithoutValueInThePreviewLocaleShowsNone() = myFixture.runWithConfig(Config(previewLocale = "en")) {
        addMenus()
        val offered = complete("'menu:menu.<caret>'")
        assertTrue("menu:menu.frOnly" in offered, offered.toString())
        assertNull(offered["menu:menu.frOnly"])
        assertEquals("Home", offered["menu:menu.home"])
    }

    /** A plural is still offered once, with the value of its first form, as the inlay hint shows it. */
    @Test
    fun testAPluralIsOfferedOnceWithItsFirstForm() = myFixture.runWithConfig(Config(previewLocale = "en")) {
        addFileToProject("locales/en/cart.json", """{"cart": {"item_one": "One item", "item_other": "{{count}} items", "empty": "Empty"}}""")
        val offered = complete("'cart:cart.<caret>'")
        assertEquals(setOf("cart:cart.item", "cart:cart.empty"), offered.keys)
        assertEquals("One item", offered["cart:cart.item"])
    }

    /** A long value is cut as the table cuts it, and stays searchable past the cut. */
    @Test
    fun testALongValueIsCutButSearchableInFull() = myFixture.runWithConfig(Config(previewLocale = "en")) {
        val long = "word ".repeat(100) + "zzTail"
        addFileToProject("locales/en/text.json", """{"text": {"long": "$long", "short": "Short"}}""")
        val offered = complete("'text:text.<caret>'")
        val shown = offered["text:text.long"].orEmpty()
        assertTrue(shown.endsWith("…") && shown.length < long.length, shown)
        val lookup = myFixture.lookupElements.orEmpty().single { it.lookupString == "text:text.long" }
        assertTrue(lookup.allLookupStrings.any { it.endsWith("zzTail") }, lookup.allLookupStrings.toString())
    }
}