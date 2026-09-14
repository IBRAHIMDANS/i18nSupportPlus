package com.ibrahimdans.i18n.plugin.ide.preview

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.openapi.wm.StatusBarWidgetFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreviewLocaleSwitchTest : PlatformBaseTest() {

    @Test
    fun `switching writes the preview locale and the folding language together`() = myFixture.runWithConfig(Config(previewLocale = "", foldingPreferredLanguage = "en")) {
        PreviewLocaleSwitcher.switchTo(project, "fr")

        val config = Settings.getInstance(project).config()
        assertEquals("fr", config.previewLocale)
        // Folding follows, so everything inline reads in one locale.
        assertEquals("fr", config.foldingPreferredLanguage)
        assertEquals("fr", PreviewLocaleSwitcher.effective(config))
    }

    @Test
    fun `the status bar widget is registered and reads the effective locale`() = myFixture.runWithConfig(Config(previewLocale = "fr")) {
        val factory = StatusBarWidgetFactory.EP_NAME.extensionList.single { it.id == PreviewLocaleSwitcher.WIDGET_ID }
        assertTrue(factory.isAvailable(project))
        val widget = factory.createWidget(project) as PreviewLocaleWidget
        assertTrue(widget.getSelectedValue().endsWith("fr"), widget.getSelectedValue())
    }
}
