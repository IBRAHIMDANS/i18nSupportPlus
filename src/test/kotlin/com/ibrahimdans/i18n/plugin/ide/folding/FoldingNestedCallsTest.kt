package com.ibrahimdans.i18n.plugin.ide.folding

import com.ibrahimdans.i18n.extensions.lang.js.JsFoldingBuilder
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `t()` call nested in a ternary or an initializer, or holding a ternary key, folds like a
 * plain call (reported against Easy I18n #593), with one region per call.
 */
internal class FoldingNestedCallsTest : PlatformBaseTest() {

    private val config = Config(foldingPreferredLanguage = "en", foldingMaxLength = 20, foldingEnabled = true)

    private fun regions(): List<Pair<String, String>> = ReadAction.compute<List<Pair<String, String>>, RuntimeException> {
        val text = myFixture.editor.document.text
        JsFoldingBuilder()
            .buildFoldRegions(myFixture.file, myFixture.editor.document, false)
            .map { it.range.substring(text) to it.placeholderText.orEmpty() }
    }

    private fun withTranslations(body: () -> Unit) = myFixture.runWithConfig(config) {
        addFileToProject("assets/en/test.json", """{"a": "Alpha", "b": "Beta"}""")
        body()
    }

    @Test
    fun callsInBothBranchesOfATernaryAreFolded() = withTranslations {
        myFixture.configureByText("ternary.js", "const label = cond ? t('test:a') : t('test:b');")

        assertEquals(listOf("t('test:a')" to "Alpha", "t('test:b')" to "Beta"), regions())
    }

    @Test
    fun callInAVariableInitializerIsFolded() = withTranslations {
        myFixture.configureByText("init.js", "const label = t('test:a');")

        assertEquals(listOf("t('test:a')" to "Alpha"), regions())
    }

    @Test
    fun callsPassedAsArgumentsOfAnotherCallAreFolded() = withTranslations {
        myFixture.configureByText("nested.js", "console.log(t('test:a'), t('test:b'));")

        assertEquals(listOf("t('test:a')" to "Alpha", "t('test:b')" to "Beta"), regions())
    }

    @Test
    fun ternaryKeyFoldsEachBranchWithoutDuplicateRegion() = withTranslations {
        myFixture.configureByText("key.js", "const label = t(cond ? 'test:a' : 'test:b');")

        assertEquals(listOf("'test:a'" to "Alpha", "'test:b'" to "Beta"), regions())
    }

    @Test
    fun nestedParenthesizedTernaryKeyFoldsEachBranch() = withTranslations {
        myFixture.configureByText("deep.js", "t(x ? (y ? 'test:a' : 'test:b') : 'test:a');")

        assertEquals(listOf("'test:a'" to "Alpha", "'test:b'" to "Beta", "'test:a'" to "Alpha"), regions())
    }

    @Test
    fun literalInTheTernaryConditionIsNotFolded() = withTranslations {
        myFixture.configureByText("cond.js", "t(mode === 'test:a' ? 'test:b' : 'test:b');")

        assertEquals(listOf("'test:b'" to "Beta", "'test:b'" to "Beta"), regions())
    }
}
