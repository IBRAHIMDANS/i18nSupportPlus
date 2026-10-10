package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.openapi.util.JDOMUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JTextField

/**
 * Machine translation is off until a project opts in, and its API keys never reach the project files.
 */
class MachineTranslationSettingsTest {

    private class MemoryKeys : ApiKeyStore {
        val keys = mutableMapOf<String, String>()
        override fun get(engineId: String) = keys[engineId].orEmpty()
        override fun set(engineId: String, key: String) { keys[engineId] = key }
    }

    private val deepl = EngineState.of(TranslationEngines.preset("deepl")!!)

    @Test
    fun `machine translation is off by default`() {
        val settings = MachineTranslationSettings()

        assertFalse(settings.state.enabled)
        assertNull(settings.provider(MemoryKeys()), "no provider before the project opts in")
        settings.loadState(MachineTranslationSettings.Settings(enabled = true))
        assertNull(settings.provider(MemoryKeys()), "no provider without an engine")
    }

    @Test
    fun `the stored state round-trips and holds no API key`() {
        val settings = MachineTranslationSettings.Settings(enabled = true, engines = mutableListOf(deepl))
        val xml = JDOMUtil.write(XmlSerializer.serialize(settings))

        assertFalse("secret" in xml)
        assertEquals(settings, XmlSerializer.deserialize(JDOMUtil.load(xml), MachineTranslationSettings.Settings::class.java))
    }

    @Test
    fun `the provider sends the key from the key store`() {
        val keys = MemoryKeys().apply { keys[deepl.id] = "secret" }
        val settings = MachineTranslationSettings().apply { loadState(MachineTranslationSettings.Settings(true, mutableListOf(deepl))) }
        var header: String? = null
        val transport = HttpTransport { _, headers, _ ->
            header = headers["Authorization"]
            HttpTransport.Response(200, """{"translations":[{"text":"Bonjour"}]}""")
        }

        assertEquals(listOf(Translation.Done("Bonjour")), settings.provider(keys, transport)!!.translate(TranslationRequest(listOf("Hello"), "en", "fr")))
        assertEquals("DeepL-Auth-Key secret", header)
    }

    @Test
    fun `an entry keeps its fields and reads its headers line by line`() {
        val config = deepl.copy(headers = "Authorization: Bearer {apiKey}\nX-Team:  web \nbroken line").toConfig()

        assertEquals(mapOf("Authorization" to "Bearer {apiKey}", "X-Team" to "web"), config.headers)
        assertEquals(TranslationEngines.preset("deepl")!!.body, config.body)
        assertNotEquals(EngineState.of(TranslationEngines.preset("deepl")!!).id, deepl.id, "each entry has an id of its own")
    }

    @Test
    fun `an entry keeps its preset's batch form while it sends the preset's request`() {
        Assertions.assertEquals(TranslationEngines.preset("deepl")!!.batch, deepl.copy(url = "https://api.deepl.com/v2/translate").toConfig().batch, "a Pro URL keeps it")
        Assertions.assertNull(deepl.copy(body = """{"text":["{text}"]}""").toConfig().batch, "a rewritten body drops it")
        Assertions.assertNull(EngineState.of(TranslationEngines.preset("libretranslate")!!).toConfig().batch, "LibreTranslate has none")
    }

    private fun find(root: Component, name: String): Component? {
        if (root.name == name) return root
        return (root as? Container)?.components?.firstNotNullOfOrNull { find(it, name) }
    }

    @Test
    fun `the form edits the copy and keeps typed keys apart`() {
        val copy = MachineTranslationSettings.Settings(enabled = true, engines = mutableListOf(deepl))
        val panel = MachineTranslationPanel(copy, { "" }) { _, _, _ -> }

        (find(panel, PluginBundle.message("settings.translate.name")) as JTextField).text = "DeepL Pro"
        panel.keyField.text = "secret"

        assertEquals("DeepL Pro", copy.engines.single().name)
        assertEquals(mapOf(deepl.id to "secret"), panel.keys)
        assertFalse("secret" in copy.toString(), "the key stays out of the stored state")
    }

    @Test
    fun `choosing a preset fills the engine and keeps its id`() {
        val copy = MachineTranslationSettings.Settings(enabled = true, engines = mutableListOf(deepl))
        val panel = MachineTranslationPanel(copy, { "" }) { _, _, _ -> }

        (find(panel, PluginBundle.message("settings.translate.preset")) as JComboBox<*>).selectedItem = "libretranslate"

        val engine = copy.engines.single()
        assertEquals(deepl.id, engine.id)
        assertEquals("libretranslate", engine.preset)
        assertEquals(TranslationEngines.preset("libretranslate")!!.url, engine.url)
    }

    @Test
    fun `Test reports the sample's translation or the reason`() {
        val copy = MachineTranslationSettings.Settings(enabled = true, engines = mutableListOf(deepl))
        var tested: Pair<EngineConfig, String>? = null
        val panel = MachineTranslationPanel(copy, { "stored" }) { engine, key, report -> tested = engine to key; report("result") }

        (find(panel, "translate.test") as JButton).doClick()

        assertEquals("stored", tested!!.second, "the stored key is used when none was typed")
        assertEquals("result", panel.testResult.text)
        val done = MachineTranslationConfigurable.testMessage(HttpTranslationProvider(deepl.toConfig(), "k") { _, _, _ ->
            HttpTransport.Response(200, """{"translations":[{"text":"Bonjour <x id=\"0\"/>"}]}""")
        })
        assertEquals(PluginBundle.message("settings.translate.test.done", "Bonjour {{name}}"), done)
        val failed = MachineTranslationConfigurable.testMessage(HttpTranslationProvider(deepl.toConfig(), "k") { _, _, _ -> HttpTransport.Response(403, "") })
        assertEquals(PluginBundle.message("translate.error.key", deepl.name), failed)
        assertTrue(done.isNotBlank())
    }
}
