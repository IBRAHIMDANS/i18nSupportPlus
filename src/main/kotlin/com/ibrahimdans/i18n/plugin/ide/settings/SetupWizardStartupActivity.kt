package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.settings.SetupNeedDetector.SetupNeed
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key

/**
 * Startup activity that suggests the SetupWizardDialog when the project's i18n configuration
 * is missing, or present but resolving to nothing — see [SetupNeedDetector], which holds the
 * rule and is where it is tested.
 *
 * A modal dialog must never be opened from a startup activity: it blocks the IDE
 * (Marketplace verification times out and reports the Trial widget as removed).
 * Instead, we post a non-blocking notification whose action opens the wizard.
 *
 * Registered in plugin.xml as a <postStartupActivity>.
 */
class SetupWizardStartupActivity : ProjectActivity {

    private companion object {
        /**
         * Marks the project as already told. The suggestion is worth one notification per
         * project opening, not one per pass — the widened rule keeps returning true until the
         * user acts on it.
         */
        val SUGGESTED: Key<Boolean> = Key.create("i18n.setupWizardSuggested")
    }

    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        if (project.getUserData(SUGGESTED) == true) return
        val need = detectNeed(project)
        if (need == SetupNeed.NONE) return
        project.putUserData(SUGGESTED, true)

        NotificationGroupManager.getInstance()
            .getNotificationGroup("i18n Support Plus")
            .createNotification(
                PluginBundle.message("wizard.notification.title"),
                content(need),
                NotificationType.INFORMATION
            )
            .addAction(NotificationAction.createSimpleExpiring(PluginBundle.message("wizard.notification.action.run")) {
                if (!project.isDisposed) SetupWizardDialog(project).show()
            })
            .addAction(NotificationAction.createSimpleExpiring(PluginBundle.message("wizard.notification.action.dismiss")) {
                // The settings checkbox is the only switch: "Don't show again" unticks it, and
                // ticking it back is what brings the suggestion back.
                Settings.getInstance(project).setupWizardEnabled = false
            })
            .notify(project)
    }

    private fun detectNeed(project: Project): SetupNeed =
        SetupNeedDetector.detect(Settings.getInstance(project).config()) { root ->
            SetupNeedDetector.holdsTranslations(project.basePath, root)
        }

    /** Saying "nothing was detected" to someone whose root simply moved sends them nowhere. */
    private fun content(need: SetupNeed): String = when (need) {
        SetupNeed.UNRESOLVED -> PluginBundle.message("wizard.notification.content.unresolved")
        else -> PluginBundle.message("wizard.notification.content")
    }
}
