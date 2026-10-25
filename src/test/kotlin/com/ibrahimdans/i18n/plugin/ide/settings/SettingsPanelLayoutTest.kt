package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JTextField

/**
 * What the main settings page holds and in what order, read off the component tree — no frame is
 * shown, so it runs headless, unlike [SettingsPanelTest].
 */
class SettingsPanelLayoutTest {

    private fun rootPanel(settings: Settings = Settings()): Container {
        // Settings.config() resolves the LOCALIZATION extension point when preferredLocalization
        // is empty; outside a platform fixture that lookup throws.
        settings.preferredLocalization = "json"
        return SettingsPanel(settings, mockk()).getRootPanel()
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { child -> listOf(child) + ((child as? Container)?.let(::descendants) ?: emptyList()) }

    private fun named(root: Container, name: String): Component? = descendants(root).firstOrNull { it.name == name }

    private fun labels(root: Container): List<String> = descendants(root).mapNotNull { (it as? JLabel)?.text }

    @Test
    fun `modules and rules are no longer on the main page`() {
        val root = rootPanel()

        assertEquals(null, named(root, "modules.list"))
        assertFalse(labels(root).contains(PluginBundle.message("settings.modules.label")))
        assertFalse(labels(root).contains(PluginBundle.message("settings.rules.label")))
    }

    @Test
    fun `the partially translated warning sits with the other checks, and the Inspections group is gone`() {
        val labels = labels(rootPanel())

        assertFalse(labels.contains(PluginBundle.message("settings.group.inspections")))
        val checks = labels.indexOf(PluginBundle.message("settings.group.checks"))
        assertTrue(checks >= 0)
        assertTrue(labels.indexOf(PluginBundle.message("settings.ignored.locales")) > checks)
        assertTrue(labels.indexOf(PluginBundle.message("settings.kept.keys")) > checks)
    }

    @Test
    fun `the setting shared by every project closes the page, under its own title`() {
        val root = rootPanel()
        val labels = labels(root)

        assertTrue(labels.indexOf(PluginBundle.message("settings.group.all.projects")) >
            labels.indexOf(PluginBundle.message("settings.group.appearance")))
        assertTrue(named(root, PluginBundle.message("settings.announce.updates")) != null)
    }

    @Test
    fun `the locale fields are lists that can still be typed into`() {
        val root = rootPanel()

        listOf("settings.folding.preferredLanguage", "settings.preview.locale").forEach { key ->
            val field = named(root, PluginBundle.message(key)) as JTextField
            val combo = field.parent as JComboBox<*>
            assertTrue(combo.isEditable, key)
        }
    }

    @Test
    fun `the locales offered are listed without changing the settings`() {
        val settings = Settings().apply { foldingPreferredLanguage = "en"; previewLocale = "" }
        settings.preferredLocalization = "json"
        val page = SettingsPanel(settings, mockk())
        val root = page.getRootPanel()

        page.offerLocales(listOf("de", "en", "fr"))

        val combo = named(root, PluginBundle.message("settings.preview.locale"))!!.parent as JComboBox<*>
        assertEquals(3, combo.itemCount)
        assertEquals("en", settings.foldingPreferredLanguage)
        assertEquals("", settings.previewLocale)
    }
}
