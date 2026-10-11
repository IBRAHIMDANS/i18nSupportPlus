package com.ibrahimdans.i18n.plugin.ide.whatsnew

import com.ibrahimdans.i18n.plugin.ide.whatsnew.WhatsNewDecider.Decision
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Tells the user, once per plugin version, that the plugin was updated; its *What's New* button
 * opens the notes of the versions installed since the last one announced, in an editor tab
 * ([ShowWhatsNewAction.open]). Nothing opens on its own.
 *
 * The last announced version is stored at application level: the notification is due once per
 * update, not once per project opened. *Don't Show Again* mutes it, at the same level, until
 * *Announce plugin updates* is ticked again in the settings.
 * Rule in [WhatsNewDecider].
 *
 * Registered in plugin.xml as a <postStartupActivity>.
 */
class WhatsNewStartupActivity : ProjectActivity {

    private companion object {
        const val LAST_SEEN_VERSION = "com.ibrahimdans.i18n.whatsNew.lastSeenVersion"
        val LOCK = Any()
    }

    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        // From a build-time resource: see PluginVersion for why the platform is not asked.
        val currentVersion = PluginVersion.current ?: return
        val properties = PropertiesComponent.getInstance()

        // Recorded before notifying: several projects opening together must not each announce it.
        // The version it replaces is kept for the page, which lists every version skipped since.
        val (previousVersion, decision) = synchronized(LOCK) {
            val lastSeen = properties.getValue(LAST_SEEN_VERSION)
            lastSeen to WhatsNewDecider.decide(lastSeen, currentVersion, !WhatsNewPreferences.announceUpdates).also {
                if (it != Decision.NONE) properties.setValue(LAST_SEEN_VERSION, currentVersion)
            }
        }
        if (decision != Decision.NOTIFY) return

        NotificationGroupManager.getInstance()
            .getNotificationGroup("i18n Support Plus")
            .createNotification(
                PluginBundle.message("whatsNew.notification.title", currentVersion),
                PluginBundle.message("whatsNew.notification.content"),
                NotificationType.INFORMATION
            )
            .addAction(NotificationAction.createSimpleExpiring(PluginBundle.message("whatsNew.notification.action.open")) {
                ShowWhatsNewAction.open(project, previousVersion)
            })
            // Mutes every later announcement, application-wide, like the version it compares with.
            .addAction(NotificationAction.createSimpleExpiring(PluginBundle.message("whatsNew.notification.action.mute")) {
                WhatsNewPreferences.announceUpdates = false
            })
            .notify(project)
    }
}
