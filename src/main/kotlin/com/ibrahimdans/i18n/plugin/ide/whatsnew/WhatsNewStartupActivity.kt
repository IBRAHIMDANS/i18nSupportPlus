package com.ibrahimdans.i18n.plugin.ide.whatsnew

import com.ibrahimdans.i18n.plugin.ide.whatsnew.WhatsNewDecider.Decision
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Tells the user, once per plugin version, that the plugin was updated and where the changes are
 * described — the change notes were only visible on the Marketplace page.
 *
 * The last announced version is stored at application level: the notification is due once per
 * update, not once per project opened. *Don't Show Again* mutes it for good, at the same level.
 * Rule in [WhatsNewDecider].
 *
 * Registered in plugin.xml as a <postStartupActivity>.
 */
class WhatsNewStartupActivity : ProjectActivity {

    private companion object {
        const val PLUGIN_ID = "com.ibrahimdans.i18n"
        const val LAST_SEEN_VERSION = "com.ibrahimdans.i18n.whatsNew.lastSeenVersion"
        const val MUTED = "com.ibrahimdans.i18n.whatsNew.muted"
        const val CHANGELOG_URL = "https://github.com/IBRAHIMDANS/i18nSupportPlus/blob/main/CHANGELOG.md"
        val LOCK = Any()
    }

    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val currentVersion = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: return
        val properties = PropertiesComponent.getInstance()

        // Recorded before notifying: several projects opening together must not each announce it.
        val decision = synchronized(LOCK) {
            WhatsNewDecider.decide(properties.getValue(LAST_SEEN_VERSION), currentVersion, properties.getBoolean(MUTED)).also {
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
                BrowserUtil.browse(CHANGELOG_URL)
            })
            // Mutes every later announcement, application-wide, like the version it compares with.
            .addAction(NotificationAction.createSimpleExpiring(PluginBundle.message("whatsNew.notification.action.mute")) {
                properties.setValue(MUTED, true)
            })
            .notify(project)
    }
}
