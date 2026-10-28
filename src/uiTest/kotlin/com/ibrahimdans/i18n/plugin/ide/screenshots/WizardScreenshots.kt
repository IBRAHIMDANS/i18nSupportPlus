package com.ibrahimdans.i18n.plugin.ide.screenshots

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.boundsOnScreen
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.elements.DialogUiComponent
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.isDialogOpened
import com.intellij.driver.sdk.ui.ui
import kotlin.time.Duration.Companion.minutes

/** The three steps of the setup wizard, on the example with no plugin settings yet. */
object WizardScreenshots {

    val SESSION = ScreenshotSession(
        "wizard",
        listOf(
            Screenshot("Setup-wizard-step-1.png") {
                wizard().boundsOnScreen
            },
            Screenshot("Setup-wizard-step-2.png") {
                val wizard = wizard()
                wizard.pressButton("Next")
                wizard.waitOneText("Step 2 — Translation Files Found", timeout = 1.minutes)
                wizard.boundsOnScreen
            },
            Screenshot(
                "Setup-wizard-step-3.png",
                cleanUp = { if (driver.ui.isDialogOpened()) wizard().pressButton("Configure later") },
            ) {
                val wizard = wizard()
                if (!wizard.hasText("Step 2 — Translation Files Found")) wizard.pressButton("Next")
                wizard.waitOneText("Step 2 — Translation Files Found", timeout = 1.minutes)
                wizard.pressButton("Next")
                wizard.waitOneText("Translations root:", timeout = 1.minutes)
                wizard.boundsOnScreen
            },
        ),
        pluginSettings = null,
    )

    /** The open wizard, or a new one started from Tools | i18n Support Plus. */
    private fun ScreenshotScope.wizard(): DialogUiComponent {
        if (!driver.ui.isDialogOpened()) {
            // The frame as context: without a project in it, the action is disabled.
            driver.invokeAction("com.ibrahimdans.i18n.RunSetupWizard", now = false, component = frame.component)
        }
        return driver.ui.dialog().also { it.waitFound(1.minutes) }
    }
}
