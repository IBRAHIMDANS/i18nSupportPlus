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
    fun `without a declared locale, the project's folding language is used`() {
        assertEquals("fr", ReferenceLocale.of(ModuleConfig(name = "web"), Config(foldingPreferredLanguage = "fr"), labels))
        assertEquals("fr", ReferenceLocale.of(null, Config(foldingPreferredLanguage = "fr"), labels))
    }

    /** Looking at another locale from the status bar must not change what the others are compared against. */
    @Test
    fun `the preview locale never decides the reference`() {
        val config = Config(previewLocale = "de", foldingPreferredLanguage = "fr")
        assertEquals("fr", ReferenceLocale.wanted(ModuleConfig(name = "web"), config))
        assertEquals("en", ReferenceLocale.wanted(null, Config(previewLocale = "de", foldingPreferredLanguage = "")))
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

    @Test
    fun `without a module, the lone configured module's locale comes first`() {
        val config = Config(previewLocale = "de", modules = listOf(ModuleConfig(name = "web", rootDirectory = "apps/web", referenceLocale = "fr")))
        assertEquals("fr", ReferenceLocale.of(null, config, labels))
    }

    @Test
    fun `without a module, several modules or a module without root leave the project-wide settings`() {
        val two = Config(foldingPreferredLanguage = "de", modules = listOf(
            ModuleConfig(name = "web", rootDirectory = "apps/web", referenceLocale = "fr"),
            ModuleConfig(name = "admin", rootDirectory = "apps/admin", referenceLocale = "fr"),
        ))
        assertEquals("de", ReferenceLocale.of(null, two, labels))
        val rootless = Config(foldingPreferredLanguage = "de", modules = listOf(ModuleConfig(name = "web", referenceLocale = "fr")))
        assertEquals("de", ReferenceLocale.of(null, rootless, labels))
    }
}
