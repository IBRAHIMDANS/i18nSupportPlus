package com.ibrahimdans.i18n.plugin.ide.preview

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.codeInsight.hints.declarative.impl.inlayRenderer.DeclarativeInlayRenderer
import com.intellij.codeInsight.hints.declarative.impl.views.TextInlayPresentationEntry
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.testFramework.PlatformTestUtil
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

    /**
     * Through the real highlighting pass, not the collector: the declarative inlay pass caches
     * on the PSI stamp, so a switch followed by a bare daemon restart changed nothing on screen.
     */
    @Test
    fun `switching re-renders the inlay hints in the new locale`() = myFixture.runWithConfig(Config(previewLocale = "en")) {
        addFileToProject("locales/en/common.json", """{"title": "Deposit box"}""")
        addFileToProject("locales/fr/common.json", """{"title": "Coffre-fort"}""")
        myFixture.configureByText("Page.tsx", "const x = t('common:title');")
        assertEquals(listOf("↦ Deposit box"), renderedHints())

        PreviewLocaleSwitcher.switchTo(project, "fr")
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(listOf("↦ Coffre-fort"), renderedHints())
    }

    /** The entries are Kotlin-private on the list but exposed as `getEntries()` to the JVM. */
    private fun textOf(renderer: DeclarativeInlayRenderer): String {
        val list = renderer.presentationList
        val entries = list.javaClass.getMethod("getEntries").invoke(list) as Array<*>
        return entries.filterIsInstance<TextInlayPresentationEntry>().joinToString("") { it.text }
    }

    private fun renderedHints(): List<String> {
        myFixture.doHighlighting()
        return ReadAction.compute<List<String>, RuntimeException> {
            myFixture.editor.inlayModel.getInlineElementsInRange(0, myFixture.file.textLength)
                .mapNotNull { it.renderer as? DeclarativeInlayRenderer }
                .map { renderer -> textOf(renderer) }
        }
    }

    @Test
    fun `the status bar widget is registered and reads the effective locale`() = myFixture.runWithConfig(Config(previewLocale = "fr")) {
        val factory = StatusBarWidgetFactory.EP_NAME.extensionList.single { it.id == PreviewLocaleSwitcher.WIDGET_ID }
        assertTrue(factory.isAvailable(project))
        val widget = factory.createWidget(project) as PreviewLocaleWidget
        assertTrue(widget.getSelectedValue().endsWith("fr"), widget.getSelectedValue())
    }
}
