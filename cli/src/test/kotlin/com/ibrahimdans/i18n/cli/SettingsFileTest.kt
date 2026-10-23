package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
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

    @Test
    fun `reads i18n-check json, with the names of the IDE's settings`(@TempDir project: Path) {
        Files.writeString(
            project.resolve(SettingsFile.JSON_PATH),
            """{ "keySeparator": "/", "flatKeys": true, "modules": [{ "name": "web", "rootDirectory": "locales", "referenceLocale": "fr" }] }""",
        )
        val config = SettingsFile.read(project)
        assertEquals(Config().copy(keySeparator = "/", flatKeys = true, modules = listOf(ModuleConfig(name = "web", rootDirectory = "locales", referenceLocale = "fr"))), config)
    }

    @Test
    fun `i18n-check json wins over the IDE's settings`(@TempDir project: Path) {
        Files.createDirectories(project.resolve(".idea"))
        Files.copy(Path.of("src/test/resources/settings").resolve(SettingsFile.PATH), project.resolve(SettingsFile.PATH))
        Files.writeString(project.resolve(SettingsFile.JSON_PATH), """{ "nsSeparator": "::" }""")
        assertEquals(Config().copy(nsSeparator = "::"), SettingsFile.read(project))
    }

    @Test
    fun `an unknown setting in i18n-check json - an error naming the file and the setting`(@TempDir project: Path) {
        Files.writeString(project.resolve(SettingsFile.JSON_PATH), """{ "keySeperator": "/" }""")
        val error = assertThrows<IllegalArgumentException> { SettingsFile.read(project) }
        assertTrue(error.message!!.startsWith("i18n-check.json: unknown setting 'keySeperator'"), error.message)
    }

    @Test
    fun `i18n-check json that does not parse - an error naming the file`(@TempDir project: Path) {
        Files.writeString(project.resolve(SettingsFile.JSON_PATH), """{ "keySeparator": """)
        val error = assertThrows<IllegalArgumentException> { SettingsFile.read(project) }
        assertTrue(error.message!!.startsWith("i18n-check.json: "), error.message)
    }
}
