package com.ibrahimdans.i18n.plugin.ide.folding

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Test

/**
 * Vue SFC folding tests.
 *
 * Verifies that ${'$'}t('key') calls inside Vue <template> blocks are folded
 * with their translation values via injected language support in JsFoldingProvider.
 */
internal class FoldingTestVue : PlatformBaseTest() {

    override fun getTestDataPath(): String = "src/test/resources/folding"

    private val testConfig = Config(foldingPreferredLanguage = "en", foldingMaxLength = 20, foldingEnabled = true)

    @Test
    fun testVueFolding() = myFixture.runWithConfig(testConfig) {
        myFixture.configureByFiles("assets/ru/test.json", "assets/en/test.json")
        myFixture.testFolding("$testDataPath/vue/simpleTest.vue")
    }

    @Test
    fun testVueFoldingDefaultNs() = myFixture.runWithConfig(testConfig) {
        myFixture.configureByFiles("assets/ru/translation.json", "assets/en/translation.json")
        myFixture.testFolding("$testDataPath/vue/defaultTestVue.vue")
    }

    @Test
    fun testVueFoldingDisabled() = myFixture.runWithConfig(Config(foldingPreferredLanguage = "fr")) {
        myFixture.configureByFiles("assets/ru/test.json", "assets/en/test.json")
        myFixture.testFolding("$testDataPath/vue/noFoldingVue.vue")
    }
}
