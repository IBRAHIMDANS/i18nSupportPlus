package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.PsiFile
import com.intellij.testFramework.TestActionEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * *Show Translations Inline* and *Switch Preview Locale* used to show in the context menu of every
 * editor; they are now offered where they apply, from the plugin's own *i18n* submenu.
 */
class I18nEditorMenuTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")

    private fun isOffered(actionId: String, file: PsiFile): Boolean {
        val action: AnAction = ActionManager.getInstance().getAction(actionId)
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.PSI_FILE, file)
            .build()
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        return event.presentation.isEnabledAndVisible
    }

    private fun withTranslations() {
        myFixture.addFileToProject("locales/en/translation.json", """{"title": "Title"}""")
    }

    @Test
    fun bothAreOfferedInACodeFileOfAProjectWithTranslations() = myFixture.runWithConfig(config) {
        withTranslations()
        val file = myFixture.addFileToProject("src/App.tsx", "const x = t('title');")
        assertTrue(isOffered(TOGGLE_FOLDING, file))
        assertTrue(isOffered(SWITCH_PREVIEW, file))
    }

    @Test
    fun neitherIsOfferedInAFileOfAnotherLanguage() = myFixture.runWithConfig(config) {
        withTranslations()
        val file = myFixture.addFileToProject("src/Main.java", "class Main {}")
        assertFalse(isOffered(TOGGLE_FOLDING, file))
        assertFalse(isOffered(SWITCH_PREVIEW, file))
    }

    @Test
    fun neitherIsOfferedInAProjectWithoutTranslations() = myFixture.runWithConfig(config) {
        val file = myFixture.addFileToProject("src/App.tsx", "const x = t('title');")
        assertFalse(isOffered(TOGGLE_FOLDING, file))
        assertFalse(isOffered(SWITCH_PREVIEW, file))
    }

    @Test
    fun aTranslationFileOffersThePreviewLocaleButNotFolding() = myFixture.runWithConfig(config) {
        withTranslations()
        val file = myFixture.addFileToProject("locales/fr/translation.json", """{"title": "Titre"}""")
        assertFalse(isOffered(TOGGLE_FOLDING, file))
        assertTrue(isOffered(SWITCH_PREVIEW, file))
    }

    @Test
    fun theEditorEntriesSitInTheSubmenuOnly() {
        val manager = ActionManager.getInstance()
        val submenu = manager.getAction("com.ibrahimdans.i18n.EditorMenu") as DefaultActionGroup
        val entries = submenu.childActionsOrStubs.mapNotNull { manager.getId(it) }
        assertEquals(EDITOR_ENTRIES, entries)

        val contextMenu = manager.getAction("EditorPopupMenu") as DefaultActionGroup
        val topLevel = contextMenu.childActionsOrStubs.mapNotNull { manager.getId(it) }
        assertTrue("com.ibrahimdans.i18n.EditorMenu" in topLevel)
        assertTrue(EDITOR_ENTRIES.none { it in topLevel }, "no entry may stay at the top of the context menu")
    }

    private companion object {
        const val TOGGLE_FOLDING = "com.ibrahimdans.i18n.ToggleFolding"
        const val SWITCH_PREVIEW = "com.ibrahimdans.i18n.SwitchPreviewLocale"
        val EDITOR_ENTRIES = listOf(
            "com.ibrahimdans.i18n.EditTranslationAtCaret",
            "com.ibrahimdans.i18n.CopyI18nKey",
            "com.ibrahimdans.i18n.MoveI18nKey",
            "com.ibrahimdans.i18n.DeleteI18nKey",
            "com.ibrahimdans.i18n.Internationalize",
            "com.ibrahimdans.i18n.SortI18nKeys",
            TOGGLE_FOLDING,
            SWITCH_PREVIEW
        )
    }
}
