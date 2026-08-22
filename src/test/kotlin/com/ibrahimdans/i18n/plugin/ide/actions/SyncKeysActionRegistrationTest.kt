package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * *Sync Keys* is now declared once and reached from two places: the *Tools* menu and the
 * tool window's toolbar, which looks the action up by [SyncKeysAction.ID] instead of calling
 * the synchronizer a second time.
 *
 * That lookup fails silently — `getAction` returns null and the toolbar simply loses its
 * button, with nothing logged — so the id and the registration have to be pinned together.
 */
class SyncKeysActionRegistrationTest : PlatformBaseTest() {

    @Test
    fun theActionIsRegisteredUnderTheIdTheToolbarLooksUp() {
        val action = ActionManager.getInstance().getAction(SyncKeysAction.ID)

        assertNotNull(action, "the toolbar resolves this id at build time and shows no button if it is null")
        assertTrue(action is SyncKeysAction, "expected SyncKeysAction, got ${action?.javaClass?.name}")
    }

    @Test
    fun theActionSitsInTheToolsMenuGroup() {
        val group = ActionManager.getInstance().getAction("com.ibrahimdans.i18n.ToolsMenu") as? DefaultActionGroup

        assertNotNull(group, "the plugin's Tools submenu must exist")
        assertTrue(
            group!!.childActionsOrStubs.any { ActionManager.getInstance().getId(it) == SyncKeysAction.ID },
            "Sync Keys must be reachable from the Tools menu, not only from the tool window"
        )
    }
}
