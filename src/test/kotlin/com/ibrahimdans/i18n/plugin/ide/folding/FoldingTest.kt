package com.ibrahimdans.i18n.plugin.ide.folding

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.testFramework.EditorTestUtil
import org.junit.jupiter.api.Assertions
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

    /** The preview locale leads, as for the inlay hints and the hover: the status bar switch folds too. */
    @Test
    fun testPreviewLocaleLeadsTheFoldingLanguage() = myFixture.runWithConfig(Config(foldingPreferredLanguage = "en", previewLocale = "ru", foldingMaxLength = 26, foldingEnabled = true)) {
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

/**
 * A key the inlay hints resolve folds into the same value: held only as plural forms
 * (`count_one` / `count_other`) or as an i18next `context` variant (`friend_male`), it had a hint
 * and no fold, the folding resolving the bare key alone.
 *
 * Read from the editor's folding manager. Assertions are qualified: `BasePlatformTestCase`
 * inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
internal class FoldingPluralAndContextTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation", foldingPreferredLanguage = "en", foldingMaxLength = 30, foldingEnabled = true)

    private fun placeholders(code: String): List<String> {
        myFixture.addFileToProject(
            "locales/en/translation.json",
            """{"files":{"count_one":"One file","count_other":"{{count}} files"},""" +
                """"users":{"count":"Users","count_one":"One user"},""" +
                """"friend":"A friend","friend_male":"A boyfriend"}"""
        )
        myFixture.configureByText("app.ts", code)
        myFixture.doHighlighting()
        EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
        return myFixture.editor.foldingModel.allFoldRegions.map { it.placeholderText }
    }

    @Test
    fun `a key held only as plural forms folds into its first form`() = myFixture.runWithConfig(config) {
        val folds = placeholders("const a = t('files.count', { count: 2 });")
        Assertions.assertTrue("One file" in folds, "folded as the hint shows it: $folds")
    }

    @Test
    fun `a key with its own value keeps it`() = myFixture.runWithConfig(config) {
        val folds = placeholders("const a = t('users.count', { count: 2 });")
        Assertions.assertTrue("Users" in folds, "the bare key's value, as before: $folds")
    }

    @Test
    fun `a key under a context folds into its variant`() = myFixture.runWithConfig(config) {
        val folds = placeholders("const a = t('friend', { context: 'male' });")
        Assertions.assertTrue("A boyfriend" in folds, "the variant, as the hint shows it: $folds")
    }

    @Test
    fun `a key under an unknown context falls back to the key`() = myFixture.runWithConfig(config) {
        val folds = placeholders("const a = t('friend', { context: 'other' });")
        Assertions.assertTrue("A friend" in folds, "i18next's fallback to the base key: $folds")
    }
}

