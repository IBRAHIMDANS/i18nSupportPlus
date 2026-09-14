package com.ibrahimdans.i18n.plugin.ide.whatsnew

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** *Don't Show Again* turns announcements off, and the settings checkbox turns them back on. */
class WhatsNewPreferencesTest : PlatformBaseTest() {

    @Test
    fun announcementsCanBeMutedAndTurnedBackOn() {
        try {
            assertTrue(WhatsNewPreferences.announceUpdates, "announced by default")
            WhatsNewPreferences.announceUpdates = false
            assertFalse(WhatsNewPreferences.announceUpdates)
            WhatsNewPreferences.announceUpdates = true
            assertTrue(WhatsNewPreferences.announceUpdates)
        } finally {
            WhatsNewPreferences.announceUpdates = true
        }
    }
}
