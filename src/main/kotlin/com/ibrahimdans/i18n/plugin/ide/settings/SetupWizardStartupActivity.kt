package com.ibrahimdans.i18n.plugin.ide.settings

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Startup activity that suggests the SetupWizardDialog when no i18n configuration
 * is detected in the project (no modules configured, default namespace still at default value).
 *
 * A modal dialog must never be opened from a startup activity: it blocks the IDE
 * (Marketplace verification times out and reports the Trial widget as removed).
 * Instead, we post a non-blocking notification whose action opens the wizard.
 *
 * Registered in plugin.xml as a <postStartupActivity>.
 */
class SetupWizardStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        if (!needsSetup(project)) return

        NotificationGroupManager.getInstance()
            .getNotificationGroup("i18n Support Plus")
            .createNotification(
                "Configure i18n Support Plus",
                "No translation configuration was detected in this project.",
                NotificationType.INFORMATION
            )
            .addAction(NotificationAction.createSimpleExpiring("Run Setup Wizard") {
                if (!project.isDisposed) SetupWizardDialog(project).show()
            })
            .addAction(NotificationAction.createSimpleExpiring("Don't show again") {
                Settings.getInstance(project).wizardDismissed = true
            })
            .notify(project)
    }

    /**
     * Returns true when the project has no i18n modules configured
     * AND the defaultNs is still the factory default ("translation").
     * This avoids showing the wizard to users who already have a config.
     */
    private fun needsSetup(project: Project): Boolean {
        val settings = Settings.getInstance(project)
        if (!settings.setupWizardEnabled) return false
        if (settings.wizardDismissed) return false
        val defaultConfig = Config()
        return settings.modules.isEmpty()
            && settings.defaultNs == defaultConfig.defaultNs
            && settings.translationsRoot.isBlank()
    }
}
