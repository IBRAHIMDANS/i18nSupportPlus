package com.ibrahimdans.i18n.plugin.ide.references.code

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiPolyVariantReference
import org.junit.jupiter.api.Test

/**
 * Ctrl+click on a key opens the preview locale's translation instead of asking which locale to go to.
 * The reference keeps every locale, which Find Usages and Rename from a translation file rely on.
 */
class I18nGotoDeclarationHandlerTest : PlatformBaseTest() {

    private fun addTranslations() {
        addFileToProject("locales/en/test.json", """{"title": "Deposit box", "item_one": "{{count}} item", "item_other": "{{count}} items"}""")
        addFileToProject("locales/fr/test.json", """{"title": "Coffre-fort", "item_one": "{{count}} élément", "item_other": "{{count}} éléments"}""")
    }

    private fun gotoTargets(key: String, file: String): List<String>? {
        myFixture.configureByText(file, "t('test:$key<caret>')")
        return ReadAction.compute<List<String>?, RuntimeException> {
            val leaf = myFixture.file.findElementAt(myFixture.caretOffset - 1)
            I18nGotoDeclarationHandler().getGotoDeclarationTargets(leaf, myFixture.caretOffset, myFixture.editor)
                ?.map { it.text.substringAfter(':').trim().unQuote() }
        }
    }

    @Test
    fun registered() {
        assertTrue(GotoDeclarationHandler.EP_NAME.extensionList.any { it is I18nGotoDeclarationHandler })
    }

    @Test
    fun opensThePreviewLocaleTranslation() = myFixture.runWithConfig(Config(previewLocale = "fr")) {
        addTranslations()
        assertEquals(listOf("Coffre-fort"), gotoTargets("title", "a.js"))
    }

    @Test
    fun followsTheFoldingLocaleWithoutAPreviewLocale() = myFixture.runWithConfig(Config(foldingPreferredLanguage = "en")) {
        addTranslations()
        assertEquals(listOf("Deposit box"), gotoTargets("title", "b.js"))
    }

    @Test
    fun opensTheFirstFormOfAPluralKey() = myFixture.runWithConfig(Config(previewLocale = "en")) {
        addTranslations()
        assertEquals(listOf("{{count}} item"), gotoTargets("item", "c.js"))
    }

    @Test
    fun leavesTheChoiceToThePlatformWhenThePreviewLocaleLacksTheKey() = myFixture.runWithConfig(Config(previewLocale = "de")) {
        addTranslations()
        assertNull(gotoTargets("title", "d.js"))
    }

    @Test
    fun theReferenceStillResolvesToEveryLocale() = myFixture.runWithConfig(Config(previewLocale = "fr")) {
        addTranslations()
        myFixture.configureByText("e.js", "t('test:title<caret>')")
        val values = ReadAction.compute<List<String>, RuntimeException> {
            myFixture.file.findElementAt(myFixture.caretOffset - 1)!!.parent.references
                .filterIsInstance<PsiPolyVariantReference>()
                .flatMap { ref -> ref.multiResolve(false).mapNotNull { it.element?.text?.substringAfter(':')?.trim()?.unQuote() } }
        }
        assertEquals(setOf("Deposit box", "Coffre-fort"), values.toSet())
        assertEquals("no target is listed twice", values.size, values.distinct().size)
    }
}
