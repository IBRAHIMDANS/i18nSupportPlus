package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Shift+F6 on a key renames it everywhere: every call site, every translation file, JSON and
 * YAML, plural forms included. It used to touch the caret's literal and JSON properties only.
 *
 * A new name is refused, nothing written, when it is taken next to the key, when it holds a
 * separator, or when a quote or a backslash in it would break a file: it used to write a second
 * `cancel` into the same object, an `a.b` property no call reads back, or an invalid literal.
 */
class RenameI18nKeyHandlerTest : PlatformBaseTest() {

    /** The messages the error dialogs showed. */
    private val shown = mutableListOf<String>()

    @AfterEach
    fun resetDialogs() {
        TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
    }

    private fun text(path: String): String = ReadAction.compute<String, RuntimeException> {
        PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!.text
    }

    private fun renameAtCaret(newSegment: String) {
        TestDialogManager.setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = newSegment
            override fun show(message: String, validator: InputValidator?): String = newSegment
        })
        TestDialogManager.setTestDialog { message -> shown += message; Messages.YES }
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

    @Test
    fun aNameTakenNextToTheKeyIsRefusedAndNothingIsWritten() {
        val translations = """{"button": {"save": "Save", "cancel": "Cancel"}}"""
        addFileToProject("locales/en/common.json", translations)
        addFileToProject("src/Second.js", "export const b = (t) => t('common:button.save');")
        myFixture.configureByText("First.js", "export const a = (t) => t('common:button.sa<caret>ve');")

        renameAtCaret("cancel")

        assertEquals(translations, text("locales/en/common.json"))
        assertTrue(text("src/Second.js").contains("t('common:button.save')"), text("src/Second.js"))
        assertEquals("export const a = (t) => t('common:button.save');", myFixture.editor.document.text)
        assertTrue(shown.single().contains("'cancel' already exists"), shown.toString())
    }

    @Test
    fun aNameTakenByAPluralGroupIsRefused() {
        val translations = """{"cart": {"save": "Save", "item_one": "{{count}} item", "item_other": "{{count}} items"}}"""
        addFileToProject("locales/en/common.json", translations)
        myFixture.configureByText("Cart.js", "export const a = (t) => t('common:cart.sa<caret>ve');")

        renameAtCaret("item")

        assertEquals(translations, text("locales/en/common.json"))
        assertEquals(1, shown.size, shown.toString())
    }

    @Test
    fun underAKeyPrefixTheNameIsLookedUpAtThePrefixedPath() {
        val translations = """{"button": {"save": "Save", "cancel": "Cancel"}}"""
        addFileToProject("locales/en/common.json", translations)
        myFixture.configureByText(
            "Prefix.tsx",
            "import { useTranslation } from 'react-i18next';\n" +
                "export const P = () => { const { t } = useTranslation('common', { keyPrefix: 'button' }); return t('sa<caret>ve'); };"
        )

        renameAtCaret("cancel")

        assertEquals(translations, text("locales/en/common.json"))
        assertEquals(1, shown.size, shown.toString())
    }

    @Test
    fun underAKeyPrefixAFreeNameIsRenamed() {
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save"}, "store": "Top-level store"}""")
        myFixture.configureByText(
            "Prefix.tsx",
            "import { useTranslation } from 'react-i18next';\n" +
                "export const P = () => { const { t } = useTranslation('common', { keyPrefix: 'button' }); return t('sa<caret>ve'); };"
        )

        renameAtCaret("store")

        assertTrue(text("locales/en/common.json").contains("\"button\": {\"store\": \"Save\"}"), text("locales/en/common.json"))
        assertTrue(myFixture.editor.document.text.contains("t('store')"), myFixture.editor.document.text)
        assertTrue(shown.isEmpty(), shown.toString())
    }

    @Test
    fun aNameHoldingTheKeySeparatorIsRefused() {
        val translations = """{"button": {"save": "Save"}}"""
        addFileToProject("locales/en/common.json", translations)
        myFixture.configureByText("First.js", "export const a = (t) => t('common:button.sa<caret>ve');")

        renameAtCaret("save.now")

        assertEquals(translations, text("locales/en/common.json"))
        assertEquals("export const a = (t) => t('common:button.save');", myFixture.editor.document.text)
        assertTrue(shown.single().contains("separator '.'"), shown.toString())
    }

    @Test
    fun aLevelNameHoldingTheKeySeparatorIsRefused() {
        val translations = """{"button": {"save": "Save"}}"""
        addFileToProject("locales/en/common.json", translations)
        myFixture.configureByText("First.js", "export const a = (t) => t('common:but<caret>ton.save');")

        renameAtCaret("actions.main")

        assertEquals(translations, text("locales/en/common.json"))
        assertTrue(shown.single().contains("separator '.'"), shown.toString())
    }

    @Test
    fun aQuoteClosingACallSiteLiteralIsRefused() {
        val translations = """{"button": {"save": "Save"}}"""
        addFileToProject("locales/en/common.json", translations)
        myFixture.configureByText("First.js", "export const a = (t) => t('common:button.sa<caret>ve');")

        renameAtCaret("don't")

        assertEquals(translations, text("locales/en/common.json"))
        assertEquals("export const a = (t) => t('common:button.save');", myFixture.editor.document.text)
        assertTrue(shown.single().contains("character '"), shown.toString())
    }

    @Test
    fun aBackslashIsRefused() {
        val translations = """{"button": {"save": "Save"}}"""
        addFileToProject("locales/en/common.json", translations)
        myFixture.configureByText("First.js", "export const a = (t) => t(\"common:button.sa<caret>ve\");")

        renameAtCaret("save\\now")

        assertEquals(translations, text("locales/en/common.json"))
        assertEquals(1, shown.size, shown.toString())
    }

    @Test
    fun aQuoteNoTextIsClosedByIsWrittenAndEveryFileStaysValid() {
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save"}}""")
        myFixture.configureByText("First.js", "export const a = (t) => t(\"common:button.sa<caret>ve\");")

        renameAtCaret("don't")

        assertTrue(text("locales/en/common.json").contains("\"don't\": \"Save\""), text("locales/en/common.json"))
        assertEquals("export const a = (t) => t(\"common:button.don't\");", myFixture.editor.document.text)
        assertTrue(shown.isEmpty(), shown.toString())
    }
}
