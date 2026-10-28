package com.ibrahimdans.i18n.plugin.ide.screenshots

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.boundsOnScreen
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.settings.SettingsDialogUiComponent
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.components.elements.JComboBoxUiComponent
import com.intellij.driver.sdk.ui.components.elements.isDialogOpened
import java.awt.Rectangle
import java.awt.Robot
import kotlin.time.Duration.Companion.minutes

/**
 * The Settings | Tools | i18n Support Plus images. The module and the rule of the last two are typed
 * for the occasion and dropped with Cancel, as the manual recipe did.
 */
object SettingsScreenshots {

    private const val PLUGIN_PAGE = "i18n Support Plus"
    // Tall enough for the main page to show from Namespaces to Checks, inside the 1400 px frame.
    private val DIALOG_BOUNDS = Rectangle(0, 0, 1400, 1400)

    val SESSION = ScreenshotSession(
        "settings",
        listOf(
            Screenshot("settings.png") {
                settingsPage(PLUGIN_PAGE).boundsOnScreen
            },
            Screenshot("settings-folding.png") {
                val dialog = settingsPage(PLUGIN_PAGE)
                // The page's last groups (Folding and preview … All projects), as a user scrolling
                // down to the bottom of it sees them.
                val content = dialog.content()
                val robot = Robot()
                robot.mouseMove(content.boundsOnScreen.centerX.toInt(), content.boundsOnScreen.centerY.toInt())
                repeat(SCROLL_NOTCHES) { robot.mouseWheel(1) }
                dialog.boundsOnScreen
            },
            Screenshot("settings-modules.png") {
                val dialog = settingsPage(PLUGIN_PAGE, "Modules")
                dialog.x { byVisibleText("Add module") }.waitFound(1.minutes).click()
                val page = dialog.content()
                page.fieldRightOf("Module name").typeOver("web")
                page.fieldRightOf("Root directory").typeOver("public/locales")
                dialog.boundsOnScreen
            },
            Screenshot(
                "settings-rules.png",
                cleanUp = { if (driver.ui.isDialogOpened()) frame.settingsDialog().pressButton("Cancel") },
            ) {
                val dialog = settingsPage(PLUGIN_PAGE, "Key Assistance Rules")
                dialog.x { byVisibleText("Add rule") }.waitFound(1.minutes).click()
                val page = dialog.content()
                page.fieldRightOf("Id").typeOver("auth-forms")
                page.fieldRightOf("Trigger").typeOver("t")
                page.fieldRightOf("Value").typeOver("auth:login.form.")
                page.fieldRightOf("Match Mode", "javax.swing.JComboBox", JComboBoxUiComponent::class.java).selectItem("Prefix")
                dialog.boundsOnScreen
            },
        ),
    )

    private const val SCROLL_NOTCHES = 60

    /** The Settings dialog, opened if needed, at fixed bounds and on the Tools page at [path]. */
    private fun ScreenshotScope.settingsPage(vararg path: String): SettingsDialogUiComponent {
        // From the project frame: IdeaFrameUI.openSettingsDialog opens the settings for new projects.
        if (!driver.ui.isDialogOpened()) driver.invokeAction("ShowSettings", now = false, component = frame.component)
        val dialog = frame.settingsDialog().also { it.waitFound(1.minutes) }
        dialog.setBounds(DIALOG_BOUNDS)
        dialog.openTreeSettingsSection("Tools", *path)
        return dialog
    }
}
