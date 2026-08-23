package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Migration of the persisted state when the two wizard switches became one.
 *
 * `wizardDismissed` was written by "Don't show again" and read by nobody else; the settings
 * checkbox drove `setupWizardEnabled`. Dropping the first flag without carrying it over would
 * hand the wizard back to every user who had already sent it away — and the `.idea` folders
 * shipped with this repository show the flag is genuinely out there.
 */
class SettingsMigrationTest : PlatformBaseTest() {

    @Test
    fun `a state carrying the old dismissal switches the wizard off`() {
        val persisted = Settings().apply { wizardDismissed = true }

        val settings = Settings()
        settings.loadState(persisted)

        assertFalse(settings.setupWizardEnabled, "the dismissal must land on the single switch")
    }

    @Test
    fun `the old flag is cleared once migrated`() {
        val persisted = Settings().apply { wizardDismissed = true }

        val settings = Settings()
        settings.loadState(persisted)

        assertFalse(settings.wizardDismissed, "a migrated flag must not be written back out")
    }

    @Test
    fun `a state without the old flag leaves the wizard on`() {
        val settings = Settings()
        settings.loadState(Settings())

        assertTrue(settings.setupWizardEnabled)
    }

    @Test
    fun `a wizard already switched off in the settings stays off`() {
        val persisted = Settings().apply { setupWizardEnabled = false }

        val settings = Settings()
        settings.loadState(persisted)

        assertFalse(settings.setupWizardEnabled)
    }
}
