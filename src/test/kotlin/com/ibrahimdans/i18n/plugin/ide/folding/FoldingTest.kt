package com.ibrahimdans.i18n.plugin.ide.folding

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Test

internal abstract class FoldingTestBase(private val lang:String, private val translationLang:String): PlatformBaseTest() {

    override fun getTestDataPath(): String {
        return "src/test/resources/folding"
    }

    private val testConfig = Config(foldingPreferredLanguage = "en", foldingMaxLength = 20, foldingEnabled = true)

    @Test
    fun testFolding() = myFixture.runWithConfig(testConfig) {
        configureByFiles("assets/ru/test.$translationLang", "assets/en/test.$translationLang")
        myFixture.testFolding("$testDataPath/$lang/simpleTest.$lang")
    }

    @Test
    fun testPreferredLanguage() = myFixture.runWithConfig(Config(foldingPreferredLanguage = "ru" ,foldingMaxLength = 26, foldingEnabled = true)) {
        configureByFiles("assets/ru/test.$translationLang", "assets/en/test.$translationLang")
        myFixture.testFolding("$testDataPath/$lang/preferredLanguageTest.$lang")
    }

    @Test
    fun testIncompleteKey() = myFixture.runWithConfig(testConfig) {
        configureByFiles("assets/ru/test.$translationLang", "assets/en/test.$translationLang")
        myFixture.testFolding("$testDataPath/$lang/incompleteKeys.$lang")
    }

    @Test
    fun testPreferredLanguageInvalidConfiguration() = myFixture.runWithConfig(Config(foldingPreferredLanguage = "fr")) {
        configureByFiles("assets/ru/test.$translationLang", "assets/en/test.$translationLang")
        myFixture.testFolding("$testDataPath/$lang/noFolding.$lang")
    }

    @Test
    fun testFoldingDisabled() = myFixture.runWithConfig(Config(foldingEnabled = false)) {
        configureByFiles("assets/ru/test.$translationLang", "assets/en/test.$translationLang")
        myFixture.testFolding("$testDataPath/$lang/noFolding.$lang")
    }

    @Test
    fun testDefaultNs() = myFixture.runWithConfig(testConfig) {
        configureByFiles("assets/ru/translation.$translationLang", "assets/en/translation.$translationLang")
        myFixture.testFolding("$testDataPath/$lang/defaultTest.$lang")
    }

    @Test
    fun testPreferredLanguageDefaultNs() = myFixture.runWithConfig(
            Config(foldingPreferredLanguage = "ru", foldingMaxLength = 28, foldingEnabled = true)
    ) {
        configureByFiles("assets/ru/translation.$translationLang", "assets/en/translation.$translationLang")
        myFixture.testFolding("$testDataPath/$lang/prefferedLangDefTest.$lang")
    }
}

internal class FoldingTestTsJson : FoldingTestBase("ts", "json")
internal class FoldingTestTsYaml : FoldingTestBase("ts", "yml")
internal class FoldingTestTsxJson : FoldingTestBase("tsx", "json")
internal class FoldingTestTsxYaml : FoldingTestBase("tsx", "yml")
internal class FoldingTestJsJson : FoldingTestBase("js", "json")
internal class FoldingTestJsYaml : FoldingTestBase("js", "yml")
internal class FoldingTestJsxJson : FoldingTestBase("jsx", "json")
internal class FoldingTestJsxYaml : FoldingTestBase("jsx", "yml")
internal class FoldingTestPhpJson : FoldingTestBase("php", "json")
internal class FoldingTestPhpYaml : FoldingTestBase("php", "yml")

