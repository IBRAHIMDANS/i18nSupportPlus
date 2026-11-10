package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.util.ReflectionUtil
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import java.util.concurrent.DelayQueue
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.text.JTextComponent
import kotlin.reflect.KMutableProperty1

/**
 * The settings page driven through its Swing components: each control is found by the name it is
 * given (its label) and typed into as a user would, key filter included. No window is opened, so
 * the test also runs headless.
 */
class SettingsPanelTest {

    private lateinit var root: JComponent

    @Test
    fun testSearchInProjectFilesOnly() {
        checkBooleanProperty(PluginBundle.getMessage("settings.search.in.project.files.only"), Settings::searchInProjectOnly)
    }

    @Test
    fun testFoldingEnabled() {
        checkBooleanProperty(PluginBundle.getMessage("settings.folding.enabled"), Settings::foldingEnabled)
    }

    @Test
    fun testGettext() {
        checkBooleanProperty(PluginBundle.getMessage("settings.gettext.enabled"), Settings::gettext)
    }

    @Test
    fun testFlatKeys() {
        checkBooleanProperty(PluginBundle.getMessage("settings.flat.keys"), Settings::flatKeys)
    }

    @Test
    fun testFirstComponentNs() {
        checkBooleanProperty(PluginBundle.getMessage("settings.first.component.ns"), Settings::firstComponentNs)
    }

    @Test
    fun testExtractSorted() {
        checkBooleanProperty(PluginBundle.getMessage("settings.extraction.sorted"), Settings::extractSorted)
    }

    @Test
    fun testPartiallyTranslated() {
        checkBooleanProperty(PluginBundle.getMessage("settings.annotations.partially.translated.enabled"), Settings::partialTranslationInspectionEnabled)
    }

    @Test
    fun testNsSeparator() {
        checkStringProperty("#", PluginBundle.getMessage("settings.namespace.separator"), Settings::nsSeparator)
    }

    @Test
    fun testInvalidSeparator() = runWithSettings(Settings()) {
        settings ->
            val message = PluginBundle.getMessage("settings.key.separator")
            val cb = textField(message)
            val value = cb.text
            assertEquals(value, settings.keySeparator)
            cb.text = ""
            cb.type(" {}\$")
            assertEquals("", settings.keySeparator)
    }

    @Test
    fun testKeySeparator() {
        checkStringProperty("@", PluginBundle.getMessage("settings.key.separator"), Settings::keySeparator)
    }

    @Test
    fun testPluralSeparator() {
        checkStringProperty("%", PluginBundle.getMessage("settings.plural.separator"), Settings::pluralSeparator)
    }

    @Test
    fun testGettextAliases() {
        checkStringProperty("alias1,alias2", PluginBundle.getMessage("settings.gettext.aliases"), Settings::gettextAliases)
    }

    @Test
    fun testDefaultNs() {
        checkStringProperty("testloc", PluginBundle.getMessage("settings.default.namespace"), Settings::defaultNs)
    }

    @Test
    fun testFoldingPreferredLanguage() {
        checkStringProperty("jp", PluginBundle.getMessage("settings.folding.preferredLanguage"), Settings::foldingPreferredLanguage)
    }

    /**
     * Was disabled because the setting stayed at 20. The field was fine: the panel is built off the
     * EDT here, so the caret stays at 0 and the backspaces the test sent deleted nothing; the 2-digit
     * limit then swallowed "17". Clearing the field first, as the text fields do, needs the empty
     * text to be ignored instead of failing `toInt()`.
     */
    @Test
    fun testFoldingMaxLength() {
        checkIntProperty("17", PluginBundle.getMessage("settings.folding.maxLength"), Settings::foldingMaxLength)
    }

    private fun checkIntProperty(keys: String, message: String, property: KMutableProperty1<Settings, Int>) = runWithSettings(Settings()) {
        settings ->
            val cb = textField(message)
            val text = cb.text
            assertEquals(text.toInt(), property.get(settings))
            cb.text = ""
            cb.type(keys)
            assertEquals(keys.toInt(), property.get(settings))
    }

    private fun checkStringProperty(keys: String, message: String, property: KMutableProperty1<Settings, String>) = runWithSettings(Settings()) {
        settings ->
            val cb = textField(message)
            val value = cb.text
            assertEquals(value, property.get(settings))
            cb.text = ""
            cb.type(keys)
            assertEquals(keys, property.get(settings))
    }

    private fun checkBooleanProperty(message: String, property: KMutableProperty1<Settings, Boolean>) = runWithSettings(Settings()) {
        settings ->
            val cb = control<AbstractButton>(message)
            val value = cb.isSelected
            assertEquals(value, property.get(settings))
            cb.doClick()
            assertEquals(!value, property.get(settings))
    }

    private fun runWithSettings(settings: Settings, block: (settings: Settings) -> Unit) {
        // Settings.config() resolves the LOCALIZATION extension point when
        // preferredLocalization is empty; outside a platform fixture that lookup
        // throws. Pre-fill it so the panel can be built in a plain JUnit test.
        settings.preferredLocalization = "json"
        root = SettingsPanel(settings, mockk()).getRootPanel()
        block(settings)
    }

    private fun textField(name: String): JTextComponent = control(name)

    /** The first component named [name], depth first, as the page lays them out. */
    private inline fun <reified T : Component> control(name: String): T {
        val found = find(root, name)
        assertNotNull(found, "no component named $name")
        return found as T
    }

    private fun find(component: Component, name: String): Component? =
        if (component.name == name) component
        else (component as? Container)?.components?.firstNotNullOfOrNull { find(it, name) }

    /**
     * Each character as a typed key: the field's key listeners may refuse it (length, refused
     * characters), otherwise it lands at the end of the text. The end, not the caret: off the EDT the
     * caret does not follow the document.
     */
    private fun JTextComponent.type(text: String) = text.forEach { char ->
        val event = KeyEvent(this, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, char)
        keyListeners.forEach { it.keyTyped(event) }
        if (!event.isConsumed) document.insertString(document.length, char.toString(), null)
    }

    @Test
    fun testSortKeysAlphabetically() {
        checkBooleanProperty(
            PluginBundle.getMessage("settings.sort.keys.alphabetically"),
            Settings::sortKeysAlphabetically
        )
    }

    @Test
    fun testPreviewLocale() {
        checkStringProperty(
            "alpha",
            PluginBundle.getMessage("settings.preview.locale"),
            Settings::previewLocale
        )
    }

    @AfterEach
    fun tearDown() {
        val timerQueueClass = Class.forName("javax.swing.TimerQueue")
        val sharedInstance = timerQueueClass.getMethod("sharedInstance")
        sharedInstance.isAccessible = true
        val timerQueue = sharedInstance.invoke(null)
        val delayQueue = ReflectionUtil.getField(timerQueueClass, timerQueue, DelayQueue::class.java, "queue")
        while(delayQueue.size>0) {
            delayQueue.poll()
        }
        unmockkAll()
    }
}
