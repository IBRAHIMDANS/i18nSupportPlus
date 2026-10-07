package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReferenceLocaleTest {

    private val labels = listOf("de", "en-US", "fr")

    @Test
    fun `the module's declared locale comes first`() {
        val module = ModuleConfig(name = "web", referenceLocale = "fr")
        assertEquals("fr", ReferenceLocale.of(module, Config(previewLocale = "de"), labels))
    }

    @Test
    fun `without a declared locale, the preview locale is used`() {
        assertEquals("de", ReferenceLocale.of(ModuleConfig(name = "web"), Config(previewLocale = "de"), labels))
    }

    @Test
    fun `without a preview locale, the folding language is used`() {
        assertEquals("fr", ReferenceLocale.of(null, Config(foldingPreferredLanguage = "fr"), labels))
    }

    @Test
    fun `with nothing set, en is wanted`() {
        assertEquals("en", ReferenceLocale.wanted(null, Config(foldingPreferredLanguage = "")))
    }

    @Test
    fun `en designates en-US files`() {
        assertEquals("en-US", ReferenceLocale.of(null, Config(), labels))
    }

    @Test
    fun `a locale with no file designates nothing`() {
        assertNull(ReferenceLocale.of(ModuleConfig(name = "web", referenceLocale = "ja"), Config(), labels))
    }

    @Test
    fun `declared ignores the project-wide settings`() {
        assertNull(ReferenceLocale.declared(null, labels))
        assertNull(ReferenceLocale.declared(ModuleConfig(name = "web"), labels))
        assertEquals("en-US", ReferenceLocale.declared(ModuleConfig(name = "web", referenceLocale = "en"), labels))
    }
}
