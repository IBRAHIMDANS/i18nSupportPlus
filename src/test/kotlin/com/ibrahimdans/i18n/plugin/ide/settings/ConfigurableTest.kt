package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

// Extends PlatformBaseTest: createComponent() reads Settings.config(), which needs the extension
// points of a running test application. Run alone, a mocked Project had none.
class ConfigurableTest : PlatformBaseTest() {

    @Test
    fun testConfigurable() {
        val configurable = Configurable(myFixture.project)
        assertEquals(PluginBundle.getMessage("app.name"), configurable.displayName)
        assertEquals("preference.i18nPlugin", configurable.helpTopic)
        assertEquals("preference.i18nPlugin", configurable.id)
        assertNotNull(configurable.createComponent())
    }
}
