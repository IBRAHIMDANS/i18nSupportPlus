package com.ibrahimdans.i18n.plugin.ide.preview

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreviewLocaleSwitcherTest {

    @Test
    fun `the effective locale is the preview locale, or the folding language when none is set`() {
        assertEquals("fr", PreviewLocaleSwitcher.effective(Config(previewLocale = "fr", foldingPreferredLanguage = "en")))
        assertEquals("en", PreviewLocaleSwitcher.effective(Config(previewLocale = "", foldingPreferredLanguage = "en")))
        assertEquals("en", PreviewLocaleSwitcher.effective(Config(previewLocale = "  ", foldingPreferredLanguage = "en")))
    }

    @Test
    fun `next cycles through the project's locales and wraps around`() {
        val locales = listOf("de", "en", "fr")
        assertEquals("en", PreviewLocaleSwitcher.next("de", locales))
        assertEquals("fr", PreviewLocaleSwitcher.next("en", locales))
        assertEquals("de", PreviewLocaleSwitcher.next("fr", locales))
    }

    @Test
    fun `a locale the project does not have starts the cycle over, and no locale leaves it alone`() {
        assertEquals("de", PreviewLocaleSwitcher.next("ja", listOf("de", "en")))
        assertEquals("en", PreviewLocaleSwitcher.next("en", emptyList()))
        assertEquals("en", PreviewLocaleSwitcher.next("en", listOf("en")))
    }
}
