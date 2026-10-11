package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.hint.HintProvider
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.waitForAsyncWork
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditTranslationAtCaretActionTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")

    /** What the dialog was opened on, in order. */
    private val opened = mutableListOf<EditTranslationAtCaretAction.Target>()

    /**
     * Stands for the dialog: records the target and, for [value], writes it into every locale
     * shown through the same [DialogViewModel.saveTranslation] the dialog's OK runs.
     */
    private fun answerDialogWith(value: String?) {
        EditTranslationAtCaretAction.opener = { project, target ->
            opened += target
            if (value != null) {
                val viewModel = DialogViewModel(project)
                viewModel.loadTranslations(target.fullKey).keys.forEach { viewModel.saveTranslation(it, target.fullKey, value) }
            }
            value != null
        }
    }

    @AfterEach
    fun restoreDialog() {
        EditTranslationAtCaretAction.opener = { project, target ->
            TranslationDialog(project, target.fullKey, target.mode, target.caller).showAndGet()
        }
    }

    private fun caretOn(path: String, marker: String) {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf(marker))
    }

    private fun targetAtCaret(): EditTranslationAtCaretAction.Target? = ReadAction.compute<EditTranslationAtCaretAction.Target?, RuntimeException> {
        EditTranslationAtCaretAction.targetAt(myFixture.file.findElementAt(myFixture.caretOffset)!!)
    }

    private fun valueAt(path: String, vararg key: String): String? = ReadAction.compute<String?, RuntimeException> {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path)) as JsonFile
        var node = file.topLevelValue as JsonObject
        for (segment in key.dropLast(1)) node = node.findProperty(segment)?.value as? JsonObject ?: return@compute null
        (node.findProperty(key.last())?.value as? JsonStringLiteral)?.value
    }

    @Test
    fun aResolvedKeyOpensTheDialogInEditMode() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home"}}""")
        addFileToProject("src/App.js", "export const label = (t) => t('common:menu.home');")
        caretOn("src/App.js", "menu.home")
        answerDialogWith(null)
        val presentation = myFixture.testAction(EditTranslationAtCaretAction())
        assertTrue(presentation.isEnabledAndVisible)
        assertEquals(1, opened.size)
        assertEquals(Mode.EDIT, opened.single().mode)
        assertEquals("common", opened.single().fullKey.ns?.text)
        assertEquals(listOf("menu", "home"), opened.single().fullKey.compositeKey.map { it.text })
    }

    @Test
    fun theActionIsAbsentOutsideAKey() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home"}}""")
        addFileToProject("src/App.js", "export const other = 'menu.home';\nexport const label = (t) => t('common:menu.home');")
        caretOn("src/App.js", "other")
        answerDialogWith("never")
        val presentation = myFixture.testAction(EditTranslationAtCaretAction())
        assertFalse(presentation.isEnabledAndVisible)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun theActionIsAbsentInATranslationFile() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home"}}""")
        caretOn("locales/en/common.json", "home")
        assertNull(targetAtCaret())
    }

    @Test
    fun theValueIsWrittenInEveryLocale() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home"}}""")
        addFileToProject("locales/fr/common.json", """{"menu": {"home": "Accueil"}}""")
        addFileToProject("src/App.js", "export const label = (t) => t('common:menu.home');")
        caretOn("src/App.js", "menu.home")
        answerDialogWith("Start")
        myFixture.testAction(EditTranslationAtCaretAction())
        waitForAsyncWork()
        assertEquals("Start", valueAt("locales/en/common.json", "menu", "home"))
        assertEquals("Start", valueAt("locales/fr/common.json", "menu", "home"))
    }

    @Test
    fun anUnresolvedKeyOpensTheDialogInCreateMode() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home"}}""")
        addFileToProject("src/App.js", "export const label = (t) => t('common:menu.missing');")
        caretOn("src/App.js", "menu.missing")
        val target = targetAtCaret()
        assertNotNull(target)
        assertEquals(Mode.CREATE, target!!.mode)
        assertEquals(listOf("menu", "missing"), target.fullKey.compositeKey.map { it.text })
        assertEquals("App.js", target.caller?.name)
    }

    /** `t('item')` is held as `item_one` / `item_other`: the dialog edits the form the hint shows. */
    @Test
    fun aPluralIsEditedThroughTheFormFound() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"item_one": "{{count}} item", "item_other": "{{count}} items"}""")
        addFileToProject("src/App.js", "export const label = (t) => t('common:item', { count: 2 });")
        caretOn("src/App.js", "item'")
        val target = targetAtCaret()!!
        assertEquals(Mode.EDIT, target.mode)
        assertTrue(target.fullKey.compositeKey.single().text.startsWith("item_"), "${target.fullKey.compositeKey}")
    }

    @Test
    fun theHintOffersAnEditLink() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home"}}""")
        addFileToProject("src/App.js", "export const label = (t) => t('common:menu.home');")
        caretOn("src/App.js", "menu.home")
        val hint = ReadAction.compute<String?, RuntimeException> {
            HintProvider().generateDoc(null, myFixture.file.findElementAt(myFixture.caretOffset))
        }
        assertNotNull(hint)
        assertTrue(hint!!.contains("psi_element://i18n-edit:"), hint)
    }

    @Test
    fun theEditLinkOpensTheDialogOnTheKey() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home"}}""")
        addFileToProject("src/App.js", "export const label = (t) => t('common:menu.home');")
        caretOn("src/App.js", "menu.home")
        val link = ReadAction.compute<String, RuntimeException> {
            val hint = HintProvider().generateDoc(null, myFixture.file.findElementAt(myFixture.caretOffset))!!
            "i18n-edit:" + hint.substringAfter("psi_element://i18n-edit:").substringBefore("'")
        }
        answerDialogWith(null)
        HintProvider().getDocumentationElementForLink(PsiManager.getInstance(project), link.replace("&amp;", "&"), null)
        waitForAsyncWork()
        assertEquals(listOf(Mode.EDIT), opened.map { it.mode })
    }
}
