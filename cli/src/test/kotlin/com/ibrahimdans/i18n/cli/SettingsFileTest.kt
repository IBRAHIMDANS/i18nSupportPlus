package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SettingsFileTest {

    @Test
    fun `reads the options set and the modules, the rest at its default`() {
        val config = SettingsFile.read(Path.of("src/test/resources/settings"))
        assertEquals(Config().copy(keySeparator = "/", modules = listOf(ModuleConfig(name = "web", rootDirectory = "locales", referenceLocale = "fr"))), config)
    }

    @Test
    fun `no settings file - the defaults`() {
        assertEquals(Config(), SettingsFile.read(Path.of("src/test/resources/flat")))
    }
}
