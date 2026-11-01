package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container

/**
 * The project's style: the form of address by locale reaches DeepL as its own parameter and an LLM as
 * a sentence; the project's instructions reach an LLM before the key's. Without them, every request is
 * the same as before.
 */
class TranslationStyleTest {

    private class Recorder(private val answer: String) : HttpTransport {
        val bodies = mutableListOf<String>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
            bodies += body
            return HttpTransport.Response(200, answer)
        }
    }

    private fun preset(id: String) = TranslationEngines.preset(id)!!

    private val deeplAnswer = """{"translations":[{"text":"Hallo"},{"text":"Tschüss"}]}"""

    private fun chat(content: String) = """{"choices":[{"message":{"content":${Gson().toJson(content)}}}]}"""

    private fun systemPrompt(body: String) =
        JsonParser.parseString(body).asJsonObject["messages"].asJsonArray[0].asJsonObject["content"].asString

    private fun formalitySent(body: String) = JsonParser.parseString(body).asJsonObject["formality"].asString

    private val informalGerman = Style(mapOf("de" to Formality.INFORMAL))

    /** [config] asked through the provider the settings build, DeepL's language codes included. */
    private fun translate(config: EngineConfig, transport: Recorder, target: String, vararg texts: String, instruction: String = "") =
        TranslationEngines.provider(listOf(config), { "k" }, transport)
            .translate(TranslationRequest(texts.toList(), "en", target, instruction = instruction))

    @Test
    fun `DeepL prefers the informal form for a locale set so, its default elsewhere`() {
        val transport = Recorder(deeplAnswer)
        val deepl = preset("deepl").copy(style = Style(mapOf("de_DE" to Formality.INFORMAL, "fr" to Formality.FORMAL)))

        translate(deepl, transport, "de", "Hello")
        translate(deepl, transport, "fr", "Hello")
        translate(deepl, transport, "ja", "Hello")

        assertEquals(listOf("prefer_less", "prefer_more", "default"), transport.bodies.map(::formalitySent))
    }

    @Test
    fun `DeepL's batch carries the form of address too`() {
        val transport = Recorder(deeplAnswer)

        val result = translate(preset("deepl").copy(style = informalGerman), transport, "de", "Hello", "Bye")

        assertEquals(listOf(Translation.Done("Hallo"), Translation.Done("Tschüss")), result)
        assertEquals("prefer_less", formalitySent(transport.bodies.single()))
    }

    @Test
    fun `DeepL's request is unchanged by the project's instructions`() {
        val plain = Recorder(deeplAnswer)
        val instructed = Recorder(deeplAnswer)

        translate(preset("deepl"), plain, "de", "Hello")
        translate(preset("deepl").copy(style = Style(instruction = "Marble is a product name")), instructed, "de", "Hello")

        assertEquals(plain.bodies, instructed.bodies)
    }

    @Test
    fun `an LLM gets the form of address, then the project's instructions, then the key's`() {
        val transport = Recorder(chat("Hallo"))
        val style = Style(mapOf("de" to Formality.INFORMAL), "Keep it short.\n\n  Marble is a product name  ")

        translate(preset("openai-compatible").copy(style = style), transport, "de", "Hello", instruction = "playful")

        val prompt = systemPrompt(transport.bodies.single())
        assertTrue(
            prompt.endsWith(
                "reply with the translation only. Use the informal form of address (like \"du\" in German or \"tu\" in French)." +
                    " Also follow these instructions from the user: Keep it short. Marble is a product name. playful."
            ),
            prompt
        )
    }

    @Test
    fun `an LLM prompt is as before without a style or for another locale`() {
        val plain = Recorder(chat("Bonjour"))
        val styled = Recorder(chat("Bonjour"))

        translate(preset("openai-compatible"), plain, "fr", "Hello")
        translate(preset("openai-compatible").copy(style = informalGerman), styled, "fr", "Hello")

        assertEquals(plain.bodies, styled.bodies)
        assertFalse("{formality}" in plain.bodies.single() || "{instruction}" in plain.bodies.single(), "no placeholder goes out")
    }

    @Test
    fun `a DeepL entry saved before the form of address sends today's request and keeps its batch form`() {
        val deepl = preset("deepl")
        val saved = EngineState.of(deepl).copy(body = deepl.body.replace(", \"formality\": \"{formality}\"", ""))

        val config = saved.toConfig()

        assertEquals(deepl.body, config.body)
        assertEquals(deepl.batch, config.batch)
        assertEquals(DeeplGlossary.KIND, config.nativeGlossary)
    }

    @Test
    fun `the form of address is written by locale and read back, the default left out`() {
        val parsed = FormalitySetting.parse("de: Informal; fr: formal; ja: polite; it: default; : formal; es")

        assertEquals(linkedMapOf("de" to "informal", "fr" to "formal"), parsed)
        assertEquals("de: informal; fr: formal", FormalitySetting.format(parsed))
        assertEquals(Style(mapOf("de" to Formality.INFORMAL, "fr" to Formality.FORMAL), "short"), FormalitySetting.style(parsed, "short"))
    }

    @Test
    fun `the form of address and the instructions are persisted with the translation settings`() {
        val settings = MachineTranslationSettings.Settings(
            enabled = true,
            formality = linkedMapOf("de" to "informal", "pt_BR" to "formal"),
            instruction = "Keep it short.\nMarble is a product name."
        )
        val xml = JDOMUtil.write(XmlSerializer.serialize(settings))
        val loaded = XmlSerializer.deserialize(JDOMUtil.load(xml), MachineTranslationSettings.Settings::class.java)

        assertEquals(settings, loaded)
        val copy = MachineTranslationSettings().apply { loadState(loaded) }.copy()
        assertEquals(settings.formality, copy.formality)
        assertEquals(settings.instruction, copy.instruction)
    }

    @Test
    fun `the provider of the settings applies the style`() {
        val service = MachineTranslationSettings().apply {
            loadState(
                MachineTranslationSettings.Settings(
                    true, mutableListOf(EngineState.of(preset("openai-compatible"))),
                    formality = linkedMapOf("de" to "informal"), instruction = "Marble is a product name"
                )
            )
        }
        val transport = Recorder(chat("Hallo"))
        val keys = object : ApiKeyStore {
            override fun get(engineId: String) = ""
            override fun set(engineId: String, key: String) = Unit
        }

        service.provider(keys, transport)!!.translate(TranslationRequest(listOf("Hello"), "en", "de"))

        val prompt = systemPrompt(transport.bodies.single())
        assertTrue("informal form of address" in prompt, prompt)
        assertTrue(prompt.endsWith("Also follow this instruction from the user: Marble is a product name."), prompt)
    }

    private fun find(root: Component, name: String): Component? {
        if (root.name == name) return root
        return (root as? Container)?.components?.firstNotNullOfOrNull { find(it, name) }
    }

    @Test
    fun `the settings page edits the form of address and the instructions of the copy`() {
        val copy = MachineTranslationSettings.Settings(enabled = true, formality = linkedMapOf("de" to "informal"))
        val panel = MachineTranslationPanel(copy, { "" }) { _, _, _ -> }
        val formality = find(panel, PluginBundle.message("settings.translate.formality")) as javax.swing.JTextField
        val instruction = find(panel, PluginBundle.message("settings.translate.instruction")) as javax.swing.JTextArea

        assertEquals("de: informal", formality.text)
        formality.text = "de: informal; fr: formal"
        instruction.text = "Marble is a product name"

        assertEquals(linkedMapOf("de" to "informal", "fr" to "formal"), copy.formality)
        assertEquals("Marble is a product name", copy.instruction)
    }
}
