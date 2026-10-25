package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The preview locale no longer decides the reference locale. A project whose modules relied on
 * it must keep the reference it had, once, on the first load after the update.
 */
class PreviewReferenceMigrationTest {

    private fun loaded(persisted: Settings): Settings = Settings().apply { loadState(persisted) }

    @Test
    fun `a preview locale the reference relied on becomes the folding language`() {
        val settings = loaded(Settings().apply {
            previewLocale = "fr"
            foldingPreferredLanguage = "en"
            modules = mutableListOf(ModuleConfig(name = "web"))
            // config() otherwise asks the LOCALIZATION extension point, absent from a plain JUnit test.
            preferredLocalization = "json"
        })

        assertEquals("fr", settings.foldingPreferredLanguage)
        assertEquals("fr", ReferenceLocale.wanted(null, settings.config()))
        assertTrue(settings.previewReferenceMigrated)
    }

    @Test
    fun `a project without modules keeps its reference too`() {
        val settings = loaded(Settings().apply { previewLocale = "fr"; foldingPreferredLanguage = "en" })

        assertEquals("fr", settings.foldingPreferredLanguage)
    }

    @Test
    fun `modules that all declare their reference leave the folding language alone`() {
        val settings = loaded(Settings().apply {
            previewLocale = "fr"
            foldingPreferredLanguage = "en"
            modules = mutableListOf(ModuleConfig(name = "web", referenceLocale = "de"), ModuleConfig(name = "admin", referenceLocale = "en"))
        })

        assertEquals("en", settings.foldingPreferredLanguage)
    }

    @Test
    fun `an empty preview locale leaves the folding language alone`() {
        val settings = loaded(Settings().apply { previewLocale = ""; foldingPreferredLanguage = "de" })

        assertEquals("de", settings.foldingPreferredLanguage)
    }

    /** Past the migration, a preview locale apart from the reference is what the status bar switch writes. */
    @Test
    fun `a migrated state is never migrated again`() {
        val settings = loaded(Settings().apply {
            previewLocale = "fr"
            foldingPreferredLanguage = "en"
            previewReferenceMigrated = true
        })

        assertEquals("en", settings.foldingPreferredLanguage)
    }

    @Test
    fun `a project with no state yet starts migrated`() {
        val settings = Settings().apply { noStateLoaded() }

        assertTrue(settings.previewReferenceMigrated)
    }
}
