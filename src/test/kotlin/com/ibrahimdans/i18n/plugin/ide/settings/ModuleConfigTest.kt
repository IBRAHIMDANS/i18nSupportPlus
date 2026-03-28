package com.ibrahimdans.i18n.plugin.ide.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ModuleConfigTest {

    @Test
    fun testNoArgConstructor() {
        val m = ModuleConfig()
        assertEquals("", m.name)
        assertEquals("", m.pathTemplate)
        assertEquals("", m.fileTemplate)
        assertEquals("", m.keyTemplate)
        assertEquals("", m.rootDirectory)
        assertEquals("", m.preset)
    }

    @Test
    fun testDefaultValues() {
        val m = ModuleConfig(name = "frontend", preset = "i18next")
        assertEquals("frontend", m.name)
        assertEquals("i18next", m.preset)
        assertEquals("", m.pathTemplate)
    }

    @Test
    fun testSettingsModulesField() {
        val settings = Settings()
        assertTrue(settings.modules.isEmpty())
        settings.modules.add(ModuleConfig(name = "frontend", preset = "i18next"))
        assertEquals(1, settings.modules.size)
        assertEquals("frontend", settings.modules[0].name)
    }

    @Test
    fun testSetConfigModules() {
        val settings = Settings()
        val moduleList = listOf(ModuleConfig(name = "backend", rootDirectory = "src/i18n"))
        settings.setConfig(Config(modules = moduleList))
        assertEquals(1, settings.modules.size)
        assertEquals("backend", settings.modules[0].name)
        assertEquals("src/i18n", settings.modules[0].rootDirectory)
    }
}
