package com.ibrahimdans.i18n.plugin.translate

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger

/** What the plugin does with [HttpTranslationProvider.glossaryWarnings]: a balloon of its group, and the log. */
object IdeGlossaryWarning : (String) -> Unit {

    private val LOG = Logger.getInstance(DeeplGlossary::class.java)

    override fun invoke(message: String) {
        LOG.warn(message)
        if (ApplicationManager.getApplication() == null) return
        NotificationGroupManager.getInstance().getNotificationGroup("i18n Support Plus")
            ?.createNotification(message, NotificationType.WARNING)
            ?.notify(null)
    }
}
