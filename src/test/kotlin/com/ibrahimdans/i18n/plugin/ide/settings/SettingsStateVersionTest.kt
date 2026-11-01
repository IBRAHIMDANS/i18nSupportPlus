package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The persisted state carries a version, and each migration is a step run on a state of a lower one.
 */
class SettingsStateVersionTest : PlatformBaseTest() {

    private fun loaded(persisted: Settings): Settings = Settings().apply { loadState(persisted) }

    @Test
    fun `a loaded state is brought to the current version`() {
        assertEquals(Settings.CURRENT_VERSION, loaded(Settings()).version)
    }

    @Test
    fun `a project with no state yet starts at the current version`() {
        assertEquals(Settings.CURRENT_VERSION, Settings().apply { noStateLoaded() }.version)
    }

    @Test
    fun `a state written before versions runs every step`() {
        val settings = loaded(Settings().apply {
            wizardDismissed = true
            previewLocale = "fr"
            foldingPreferredLanguage = "en"
        })

        assertFalse(settings.setupWizardEnabled)
        assertEquals("fr", settings.foldingPreferredLanguage)
    }

    @Test
    fun `a state of the current version runs no step`() {
        val settings = loaded(Settings().apply {
            version = Settings.CURRENT_VERSION
            previewLocale = "fr"
            foldingPreferredLanguage = "en"
        })

        Assertions.assertEquals("en", settings.foldingPreferredLanguage, "a preview locale set after the migration is the user's choice")
    }
}
