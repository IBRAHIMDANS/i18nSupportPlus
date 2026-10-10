package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.DeleteI18nKeyAction
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.waitForAsyncWork
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.Action

/**
 * *Delete* in the translation dialog: the key is removed the way *Delete i18n Key* removes it,
 * from a key rather than from an element under the caret.
 */
class TranslationDialogDeleteTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")

    private fun addPlural() {
        addFileToProject("locales/en/common.json", """{"item_one": "{{count}} item", "item_other": "{{count}} items", "other": "x"}""")
        addFileToProject("locales/fr/common.json", """{"item_one": "{{count}} article", "item_other": "{{count}} articles", "other": "y"}""")
        addFileToProject("src/App.js", "export const label = (t, n) => t('common:item', { count: n });")
    }

    private fun dialog(key: String, mode: Mode): TranslationDialog =
        TranslationDialog(project, KeysSynchronizer().buildFullKey(key, config), mode)

    private fun deleteButtonOf(dialog: TranslationDialog): Boolean =
        dialog.leftSideActions().any { it.getValue(Action.NAME) == "Delete" }

    private fun valueAt(path: String, key: String): String? = ReadAction.compute<String?, RuntimeException> {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path)) as JsonFile
        ((file.topLevelValue as JsonObject).findProperty(key)?.value as? JsonStringLiteral)?.value
    }

    @Test
    fun aPlanFromTheKeyMatchesThePlanFromTheElement() = myFixture.runWithConfig(config) {
        addPlural()
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("locales/en/common.json"))
        val element = myFixture.file.findElementAt(myFixture.file.text.indexOf("item_one"))!!

        val fromElement = ReadAction.compute<DeleteI18nKeyAction.Plan?, RuntimeException> { DeleteI18nKeyAction.planOf(element) }!!
        val fromKey = ReadAction.compute<DeleteI18nKeyAction.Plan, RuntimeException> { DeleteI18nKeyAction.planOf(project, "common:item_one", null) }

        assertEquals(setOf("common:item_one", "common:item_other"), fromKey.keys.toSet())
        assertEquals(fromElement.keys.toSet(), fromKey.keys.toSet())
        assertEquals(fromElement.usages, fromKey.usages)
    }

    @Test
    fun aKeyWithNoTranslationPlansNothing() = myFixture.runWithConfig(config) {
        addPlural()
        val plan = ReadAction.compute<DeleteI18nKeyAction.Plan, RuntimeException> { DeleteI18nKeyAction.planOf(project, "common:missing", null) }
        assertTrue(plan.keys.isEmpty())
    }

    @Test
    fun onlyTheEditDialogOffersDelete() = myFixture.runWithConfig(config) {
        addPlural()
        val edit = dialog("common:other", Mode.EDIT)
        val create = dialog("common:", Mode.CREATE)
        try {
            assertTrue(deleteButtonOf(edit))
            assertFalse(deleteButtonOf(create))
        } finally {
            Disposer.dispose(edit.disposable)
            Disposer.dispose(create.disposable)
        }
    }

    @Test
    fun deleteRemovesEveryFormFromEveryLocaleAndClosesWithOk() = myFixture.runWithConfig(config) {
        addPlural()
        val dialog = dialog("common:item_one", Mode.EDIT)
        TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            dialog.deleteKey()
            waitForAsyncWork()
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
            Disposer.dispose(dialog.disposable)
        }
        for (path in listOf("locales/en/common.json", "locales/fr/common.json")) {
            assertEquals(null, valueAt(path, "item_one"))
            assertEquals(null, valueAt(path, "item_other"))
        }
        assertEquals("y", valueAt("locales/fr/common.json", "other"))
        assertEquals(DialogWrapper.OK_EXIT_CODE, dialog.exitCode)
    }

    @Test
    fun aCancelledDeleteLeavesTheKeyAndTheDialogOpen() = myFixture.runWithConfig(config) {
        addPlural()
        val dialog = dialog("common:other", Mode.EDIT)
        TestDialogManager.setTestDialog(TestDialog.NO)
        try {
            dialog.deleteKey()
            waitForAsyncWork()
            assertFalse(dialog.isDisposed)
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
            Disposer.dispose(dialog.disposable)
        }
        assertEquals("x", valueAt("locales/en/common.json", "other"))
    }
}
