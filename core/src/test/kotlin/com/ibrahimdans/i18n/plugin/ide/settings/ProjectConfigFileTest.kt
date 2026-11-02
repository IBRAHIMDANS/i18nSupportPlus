package com.ibrahimdans.i18n.plugin.ide.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProjectConfigFileTest {

    @Test
    fun `the settings a file names are laid over the base, the others kept`() {
        val content = ProjectConfigFile.parse("""{ "keySeparator": "/", "flatKeys": true, "translationsRoot": "src/locales" }""")
        val base = Config(nsSeparator = "::", foldingEnabled = true)

        val config = content.applyTo(base)

        assertEquals(base.copy(keySeparator = "/", flatKeys = true, translationsRoot = "src/locales"), config)
    }

    @Test
    fun `a boolean may be written as a string`() {
        val config = ProjectConfigFile.parse("""{ "gettext": "true", "firstComponentNs": false }""").applyTo(Config())

        assertTrue(config.gettext)
        assertFalse(config.firstComponentNs)
    }

    @Test
    fun `the names a file sets, modules included only when it lists them`() {
        assertEquals(setOf("keySeparator"), ProjectConfigFile.parse("""{ "keySeparator": "/" }""").names)
        assertEquals(setOf("keySeparator", "modules"), ProjectConfigFile.parse("""{ "keySeparator": "/", "modules": [] }""").names)
    }

    @Test
    fun `modules replace the base's, each field missing at its default`() {
        val content = ProjectConfigFile.parse("""{ "modules": [{ "name": "web", "rootDirectory": "apps/web", "writeDefaultValue": true }] }""")
        val base = Config(modules = listOf(ModuleConfig(name = "old", rootDirectory = "old")))

        val modules = content.applyTo(base).modules

        assertEquals(listOf(ModuleConfig(name = "web", rootDirectory = "apps/web", writeDefaultValue = true)), modules)
    }

    @Test
    fun `a module set aside in the base stays aside, one set aside in the file too`() {
        val content = ProjectConfigFile.parse(
            """{ "modules": [{ "name": "web" }, { "name": "admin" }, { "name": "docs", "enabled": false }] }"""
        )
        val base = Config(modules = listOf(ModuleConfig(name = "admin", enabled = false), ModuleConfig(name = "docs", enabled = true)))

        val active = content.applyTo(base).modules.associate { it.name to it.enabled }

        assertEquals(mapOf("web" to true, "admin" to false, "docs" to false), active)
    }

    @Test
    fun `an ignored name is read but not applied`() {
        val content = ProjectConfigFile.parse("""{ "previewLocale": "fr", "keySeparator": "/" }""")

        val config = content.applyTo(Config(previewLocale = "de"), ignored = setOf(ProjectConfigFile.PREVIEW_LOCALE))

        assertEquals("de", config.previewLocale)
        assertEquals("/", config.keySeparator)
    }

    @Test
    fun `an unknown name is refused, naming it`() {
        val error = assertThrows<IllegalArgumentException> { ProjectConfigFile.parse("""{ "keySeperator": "/" }""") }

        assertTrue(error.message!!.startsWith("unknown setting 'keySeperator'"), error.message)
    }

    @Test
    fun `an unknown module field is refused too`() {
        assertThrows<IllegalArgumentException> { ProjectConfigFile.parse("""{ "modules": [{ "name": "web", "root": "x" }] }""") }
    }

    @Test
    fun `text that is no JSON object is refused`() {
        assertThrows<IllegalArgumentException> { ProjectConfigFile.parse("""[1, 2]""") }
        assertThrows<IllegalArgumentException> { ProjectConfigFile.parse("""{ "keySeparator": """) }
        assertThrows<IllegalArgumentException> { ProjectConfigFile.parse("""{ "keySeparator": ["/"] }""") }
    }

    @Test
    fun `restore puts the named settings back as stored`() {
        val stored = Config(keySeparator = ".", defaultNs = "common")
        val edited = Config(keySeparator = "/", defaultNs = "app", foldingEnabled = true)

        val restored = ProjectConfigFile.restore(edited, stored, setOf("keySeparator", "modules"))

        assertEquals(edited.copy(keySeparator = "."), restored)
    }

    @Test
    fun `the export reads back as the same settings`() {
        val config = Config(
            translationsRoot = "src/locales",
            keySeparator = "/",
            flatKeys = true,
            ignoredLocales = "de",
            regionalFallback = false,
            foldingPreferredLanguage = "fr",
            modules = listOf(ModuleConfig(name = "web", rootDirectory = "apps/web", pathTemplate = "public/locales/{lang}/{ns}.json", referenceLocale = "fr")),
        )

        val text = ProjectConfigFile.export(config)

        assertEquals(config, ProjectConfigFile.parse(text).applyTo(Config()))
    }

    @Test
    fun `the export leaves out the defaults, the preview locale and each module's Active box`() {
        val config = Config(
            keySeparator = "/",
            previewLocale = "fr",
            foldingEnabled = true,
            modules = listOf(ModuleConfig(name = "web", enabled = false)),
        )

        val text = ProjectConfigFile.export(config)

        assertEquals(
            """
            {
              "keySeparator": "/",
              "modules": [
                {
                  "name": "web"
                }
              ]
            }
            """.trimIndent() + "\n",
            text
        )
    }

    @Test
    fun `the export writes booleans as booleans`() {
        assertTrue(ProjectConfigFile.export(Config(flatKeys = true)).contains("\"flatKeys\": true"))
    }

    @Test
    fun `an empty configuration exports an empty object`() {
        assertEquals("{}\n", ProjectConfigFile.export(Config()))
    }

    @Test
    fun `other settings read without the file keep the names it takes only`() {
        val content = ProjectConfigFile.contentOf(mapOf("keySeparator" to "/", "foldingEnabled" to "true"), null)

        assertEquals(setOf("keySeparator"), content.names)
        assertNull(content.modules)
    }
}
