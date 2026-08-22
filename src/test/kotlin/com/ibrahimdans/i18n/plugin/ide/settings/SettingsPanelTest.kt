package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.util.ReflectionUtil
import io.mockk.mockk
import io.mockk.unmockkAll
import net.sourceforge.marathon.javadriver.JavaDriver
import net.sourceforge.marathon.javadriver.JavaProfile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import java.util.concurrent.DelayQueue
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.reflect.KMutableProperty1

class SettingsPanelTest {

    private lateinit var driver: JavaDriver

    @BeforeEach
    fun setUp() {
        Assumptions.assumeFalse(
            GraphicsEnvironment.isHeadless(),
            "Skipping Swing UI test in headless environment"
        )
        driver = JavaDriver(JavaProfile(JavaProfile.LaunchMode.EMBEDDED))
    }

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
            val cb = driver.findElementByName(message)
            assertNotNull(cb)
            val value = cb.text
            assertEquals(value, settings.keySeparator)
            cb.clear()
            cb.sendKeys(" {}\$")
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
     * Disabled: typing into the int field does not reach the setting. After `sendKeys("\b\b17")`
     * the field is expected to hold 17 and `foldingMaxLength` still reads 20, its default.
     *
     * The helper it uses was broken too and is fixed here — it compared against `keys.toInt()` on
     * a string containing backspaces, so this case threw NumberFormatException rather than failing
     * on the value. That is why it was commented out. With the helper corrected the real gap shows.
     *
     * Not a general binding failure: `testFoldingPreferredLanguage` drives the string field the
     * same way and passes, so it is specific to the int field — either the component ignores the
     * backspaces or its binding does not commit on keystrokes.
     */
    @Disabled
    @Test
    fun testFoldingMaxLength() {
        checkIntProperty("\b\b17", PluginBundle.getMessage("settings.folding.maxLength"), Settings::foldingMaxLength)
    }

    private fun checkIntProperty(keys: String, message: String, property: KMutableProperty1<Settings, Int>) = runWithSettings(Settings()) {
        settings ->
            val cb = driver.findElementByName(message)
            assertNotNull(cb)
            val text = cb.text
            assertEquals(text.toInt(), property.get(settings))
            cb.sendKeys(keys)
            // `keys` carries the backspaces that clear the field before typing, so it is not a
            // number: "\b\b17" typed over "20" leaves 17. Comparing against keys.toInt() threw
            // NumberFormatException every time — which is why this case was commented out rather
            // than failing. Only the digits describe the expected value.
            assertEquals(keys.filter { it.isDigit() }.toInt(), property.get(settings))
    }

    private fun checkStringProperty(keys: String, message: String, property: KMutableProperty1<Settings, String>) = runWithSettings(Settings()) {
        settings ->
            val cb = driver.findElementByName(message)
            assertNotNull(cb)
            val value = cb.text
            assertEquals(value, property.get(settings))
            cb.clear()
            cb.sendKeys(keys)
            assertEquals(keys, property.get(settings))
    }

    private fun checkBooleanProperty(message: String, property: KMutableProperty1<Settings, Boolean>) = runWithSettings(Settings()) {
        settings ->
            val cb = driver.findElementByName(message)
            assertNotNull(cb)
            val value = cb.text == "true"
            assertEquals(value, property.get(settings))
            cb.click()
            assertEquals(!value, property.get(settings))
    }

    private fun runWithSettings(settings: Settings, block: (settings: Settings) -> Unit) {
        // Settings.config() resolves the LOCALIZATION extension point when
        // preferredLocalization is empty; outside a platform fixture that lookup
        // throws. Pre-fill it so the panel can be built in a plain JUnit test.
        settings.preferredLocalization = "json"
        val frame = JFrame()
        frame.contentPane.add(SettingsPanel(settings, mockk()).getRootPanel())
        SwingUtilities.invokeLater {
            frame.pack()
            frame.isVisible = true
        }
        try {
            block(settings)
        } finally {
            frame.dispose()
        }
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
        if (::driver.isInitialized) {
            driver.quit()
        }
        unmockkAll()
    }
}
