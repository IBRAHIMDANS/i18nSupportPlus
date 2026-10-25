package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Extends PlatformBaseTest: createComponent() reads Settings.config(), which needs the extension
// points of a running test application. Run alone, a mocked Project had none.
class ConfigurableTest : PlatformBaseTest() {

    @Test
    fun testConfigurable() {
        val configurable = Configurable(myFixture.project)
        assertEquals(PluginBundle.getMessage("app.name"), configurable.displayName)
        // No help page exists for it.
        assertNull(configurable.helpTopic)
        assertEquals("preference.i18nPlugin", configurable.id)
        assertNotNull(configurable.createComponent())
        configurable.disposeUIResources()
    }

    @Test
    fun testModulesPage() {
        val settings = Settings.getInstance(myFixture.project)
        val before = settings.modules
        try {
            val page = ModulesConfigurable(myFixture.project)
            assertEquals("preference.i18nPlugin.modules", page.id)
            assertEquals(PluginBundle.getMessage("settings.modules.page.title"), page.displayName)
            assertNotNull(page.createComponent())
            assertFalse(page.isModified)

            settings.modules = mutableListOf(ModuleConfig(name = "shop", rootDirectory = "apps/shop"))
            assertTrue(page.isModified)
            page.apply()
            assertFalse(page.isModified)
            page.disposeUIResources()
        } finally {
            settings.modules = before
        }
    }

    @Test
    fun testRulesPage() {
        val settings = Settings.getInstance(myFixture.project)
        val before = settings.rules
        try {
            val page = RulesConfigurable(myFixture.project)
            assertEquals("preference.i18nPlugin.rules", page.id)
            assertEquals(PluginBundle.getMessage("settings.rules.page.title"), page.displayName)
            assertNotNull(page.createComponent())
            assertFalse(page.isModified)

            settings.rules = mutableListOf(EditorRuleState(id = "translate"))
            assertTrue(page.isModified)
            page.apply()
            assertFalse(page.isModified)
            page.disposeUIResources()
        } finally {
            settings.rules = before
        }
    }
}
