package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `i18n-check.json` at the project's root, read by the IDE: what it sets wins over the stored
 * settings, is never written back into them, and is read again when the file changes.
 */
class ProjectConfigFileSettingsTest : PlatformBaseTest() {

    private val settings: Settings get() = Settings.getInstance(project)
    private lateinit var stored: Config

    @BeforeEach
    fun keepStored() {
        stored = settings.config()
    }

    @AfterEach
    fun restoreStored() {
        deleteConfigFile()
        settings.setConfig(stored)
    }

    @Test
    fun `a setting the file names wins over the stored one`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "keySeparator": "/", "translationsRoot": "src/locales" }""")

        val config = settings.config()

        assertEquals("/", config.keySeparator)
        assertEquals("src/locales", config.translationsRoot)
        assertEquals(stored.defaultNs, config.defaultNs)
        assertEquals(mapOf("keySeparator" to "/", "translationsRoot" to "src/locales"), settings.configFileSettings())
    }

    @Test
    fun `a setting the file names is never written into the stored settings`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "keySeparator": "/" }""")

        settings.setConfig(settings.config().copy(keySeparator = "#", defaultNs = "app"))
        deleteConfigFile()

        assertEquals(stored.keySeparator, settings.config().keySeparator)
        assertEquals("app", settings.config().defaultNs)
    }

    @Test
    fun `a file saved again is read again`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "keySeparator": "/" }""")
        assertEquals("/", settings.config().keySeparator)

        val file = settings.configFileOrNull()!!
        WriteAction.runAndWait<Exception> { VfsUtil.saveText(file, """{ "keySeparator": "#" }""") }

        assertEquals("#", settings.config().keySeparator)
    }

    @Test
    fun `a file created after the settings were read is found`() {
        settings.config()

        addFileToProject(ProjectConfigFile.PATH, """{ "defaultNs": "common" }""")

        assertEquals("common", settings.config().defaultNs)
    }

    @Test
    fun `a file that does not read leaves the stored settings, without an exception`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "keySeperator": "/" }""")

        assertEquals(stored, settings.config())
        assertTrue(settings.configFileSettings().isEmpty())
    }

    @Test
    fun `the preview locale of the file is left to each developer`() {
        settings.setConfig(stored.copy(previewLocale = "de"))
        addFileToProject(ProjectConfigFile.PATH, """{ "previewLocale": "fr" }""")

        assertEquals("de", settings.config().previewLocale)
        assertTrue(settings.configFileSettings().isEmpty())
    }

    @Test
    fun `an exported file reads back as the same settings`() {
        val config = stored.copy(
            keySeparator = "/",
            ignoredLocales = "de",
            modules = listOf(ModuleConfig(name = "web", rootDirectory = "apps/web", referenceLocale = "fr")),
        )
        val dir = project.guessProjectDir()!!

        ProjectConfigFileExport.write(project, dir, null, ProjectConfigFile.export(config))

        assertEquals(config, settings.config())
    }

    private fun deleteConfigFile() {
        val file = project.guessProjectDir()?.findChild(ProjectConfigFile.PATH) ?: return
        WriteAction.runAndWait<Exception> { file.delete(this) }
    }
}
