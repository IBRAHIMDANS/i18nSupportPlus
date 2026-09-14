package com.ibrahimdans.i18n.plugin.ide.whatsnew

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager

/**
 * Whether plugin updates are announced: turned off by the notification's *Don't Show Again*, back on
 * from the settings. Stored at application level, like the last announced version.
 */
object WhatsNewPreferences {

    private const val MUTED = "com.ibrahimdans.i18n.whatsNew.muted"

    /**
     * Without a running application — the settings panel is built in a bare JFrame by its UI test —
     * updates read as announced and a change is dropped.
     */
    var announceUpdates: Boolean
        get() = properties()?.getBoolean(MUTED) != true
        set(value) {
            properties()?.setValue(MUTED, !value)
        }

    private fun properties(): PropertiesComponent? =
        if (ApplicationManager.getApplication() == null) null else PropertiesComponent.getInstance()
}
