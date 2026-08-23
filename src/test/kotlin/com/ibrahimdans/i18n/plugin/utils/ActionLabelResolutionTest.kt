package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.actionSystem.ActionManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks that the platform really does label the menu entries from the bundle.
 *
 * `ActionDeclarationBundleTest` compares two files and can only prove the keys *exist*. This
 * one asks the platform, with the plugin loaded, for the text it would put in the menu — the
 * only way to tell that `<resource-bundle>` plus `action.<id>.text` is wired up at all. An
 * unresolved key comes back as `!action.….text!`, which is exactly what a user would read.
 */
class ActionLabelResolutionTest : PlatformBaseTest() {

    private companion object {
        val ACTION_IDS = listOf(
            "com.ibrahimdans.i18n.RunSetupWizard",
            "com.ibrahimdans.i18n.ExportTranslations",
            "com.ibrahimdans.i18n.ImportTranslations",
            "com.ibrahimdans.i18n.CleanupUnusedKeys",
            "com.ibrahimdans.i18n.SyncKeys",
            "com.ibrahimdans.i18n.ToggleFolding",
            "com.ibrahimdans.i18n.BatchExtractI18n",
            "com.ibrahimdans.i18n.SortI18nKeys",
            "com.ibrahimdans.i18n.MoveI18nKey"
        )
    }

    @Test
    fun `every menu entry takes a real label from the bundle`() {
        val manager = ActionManager.getInstance()

        for (id in ACTION_IDS) {
            val action = manager.getAction(id)
            assertNotNull(action, "action $id is not registered")

            val text = action.templatePresentation.text
            assertTrue(!text.isNullOrBlank(), "action $id has no text — the bundle lookup returned nothing")
            assertFalse(
                text!!.startsWith("!") && text.endsWith("!"),
                "action $id is labelled $text — the platform could not resolve its bundle key"
            )
        }
    }

    /**
     * `ToggleFoldingAction` used to pass its text and description to `ToggleAction`'s
     * constructor. A literal set there wins over the bundle, so the entry would have stayed
     * English in a localized IDE while every other one followed the language — the exact
     * failure this whole batch exists to prevent, and one no static check can see.
     */
    @Test
    fun `the folding toggle takes no label from its constructor`() {
        val action = ActionManager.getInstance().getAction("com.ibrahimdans.i18n.ToggleFolding")

        assertTrue(
            action.templatePresentation.text == PluginBundle.getMessage("action.com.ibrahimdans.i18n.ToggleFolding.text"),
            "expected the bundle to win, got '${action.templatePresentation.text}'"
        )
        assertNotNull(action.templatePresentation.icon, "the icon moved to plugin.xml with the label")
    }

    @Test
    fun `the wizard entry reads what the bundle says`() {
        val action = ActionManager.getInstance().getAction("com.ibrahimdans.i18n.RunSetupWizard")

        assertTrue(
            action.templatePresentation.text == PluginBundle.getMessage("action.com.ibrahimdans.i18n.RunSetupWizard.text"),
            "expected the menu entry and the bundle to agree, got '${action.templatePresentation.text}'"
        )
    }
}
