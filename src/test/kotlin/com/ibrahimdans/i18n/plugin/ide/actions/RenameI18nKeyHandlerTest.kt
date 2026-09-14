package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Shift+F6 on a key renames it everywhere: every call site, every translation file, JSON and
 * YAML, plural forms included. It used to touch the caret's literal and JSON properties only.
 */
class RenameI18nKeyHandlerTest : PlatformBaseTest() {

    private fun text(path: String): String = ReadAction.compute<String, RuntimeException> {
        PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!.text
    }

    private fun renameAtCaret(newSegment: String) {
        setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = newSegment
            override fun show(message: String, validator: InputValidator?): String = newSegment
        })
        RenameI18nKeyHandler().invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)
    }

    @Test
    fun renamesEveryCallSiteAndEveryLocaleButNotAnotherKeySharingTheWord() {
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save"}}""")
        addFileToProject("locales/fr/common.json", """{"button": {"save": "Enregistrer"}}""")
        addFileToProject("locales/en/other.json", """{"button": {"save": "Other"}}""")
        addFileToProject("src/Second.js", "export const b = (t) => t('common:button.save');")
        addFileToProject("src/Unrelated.js", "export const c = (t) => t('other:button.save');")
        myFixture.configureByText("First.js", "export const a = (t) => t('common:button.sa<caret>ve');")

        renameAtCaret("store")

        assertEquals("export const a = (t) => t('common:button.store');", myFixture.editor.document.text)
        assertTrue(text("src/Second.js").contains("t('common:button.store')"), text("src/Second.js"))
        assertTrue(text("src/Unrelated.js").contains("t('other:button.save')"), "another key must stay: ${text("src/Unrelated.js")}")
        assertTrue(text("locales/en/common.json").contains("\"store\": \"Save\""), text("locales/en/common.json"))
        assertTrue(text("locales/fr/common.json").contains("\"store\": \"Enregistrer\""), text("locales/fr/common.json"))
        assertTrue(text("locales/en/other.json").contains("\"save\": \"Other\""), text("locales/en/other.json"))
    }

    @Test
    fun renamesYamlProperties() {
        addFileToProject("locales/en/common.yml", "button:\n  save: Save\n")
        myFixture.configureByText("First.js", "export const a = (t) => t('common:button.sa<caret>ve');")

        renameAtCaret("store")

        assertTrue(text("locales/en/common.yml").contains("store: Save"), text("locales/en/common.yml"))
    }

    @Test
    fun renamesEveryPluralForm() {
        addFileToProject("locales/en/common.json", """{"cart": {"item_one": "{{count}} item", "item_other": "{{count}} items"}}""")
        myFixture.configureByText("Cart.js", "export const a = (t, count) => t('common:cart.it<caret>em', { count });")

        renameAtCaret("product")

        val written = text("locales/en/common.json")
        assertTrue(written.contains("\"product_one\"") && written.contains("\"product_other\""), written)
        assertEquals("export const a = (t, count) => t('common:cart.product', { count });", myFixture.editor.document.text)
    }
}
