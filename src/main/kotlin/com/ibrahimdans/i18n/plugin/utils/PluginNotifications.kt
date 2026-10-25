package com.ibrahimdans.i18n.plugin.utils

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * The notifications of the plugin's actions, in one of two groups declared in `plugin.xml`.
 *
 * A result to read — "nothing to sync", "3 keys deleted" — fades out ([RESULTS]): it used to stay
 * on screen until closed, or came as a modal that stopped the user for nothing to decide. A
 * warning stays ([STICKY]): something was not done as asked, and the user has it to fix. Both
 * stay listed in the Notifications tool window.
 */
object PluginNotifications {

    /** Stays on screen until closed: for what asks something of the user. */
    const val STICKY = "i18n Support Plus"

    /** Fades out: for a result to read. */
    const val RESULTS = "i18n Support Plus results"

    /** Shows [content] under [title], with [actions]; an information fades out, anything else stays. */
    fun notify(
        project: Project,
        content: String,
        type: NotificationType = NotificationType.INFORMATION,
        title: String = "",
        vararg actions: NotificationAction
    ): Notification {
        val group = if (type == NotificationType.INFORMATION) RESULTS else STICKY
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(group)
            .createNotification(title, content, type)
        actions.forEach { notification.addAction(it) }
        notification.notify(project)
        return notification
    }
}
