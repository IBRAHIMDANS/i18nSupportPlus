package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.google.gson.JsonParser
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container

/**
 * The project glossary: a product name never reaches an engine, and an LLM is told how the
 * business terms are translated in the target locale. Without one, every request is as before.
 */
class TranslationGlossaryTest {

    private class Recorder(private val answer: (String) -> String) : HttpTransport {
        val bodies = mutableListOf<String>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
            bodies += body
            return HttpTransport.Response(200, answer(body))
        }
    }

    private fun preset(id: String) = TranslationEngines.preset(id)!!

    private val glossary = Glossary(listOf(
        Glossary.Term("Acme Cloud"),
        Glossary.Term("cart", mapOf("fr" to "panier", "de" to "Warenkorb")),
        Glossary.Term("checkout", mapOf("fr_FR" to "paiement"))
    ))

    /** An LLM answer: the user's text, the masked tags echoed in another order. */
    private fun chat(content: String) = """{"choices":[{"message":{"content":${com.google.gson.Gson().toJson(content)}}}]}"""

    private fun systemPrompt(body: String) =
        JsonParser.parseString(body).asJsonObject["messages"].asJsonArray[0].asJsonObject["content"].asString

    private fun userText(body: String) =
        JsonParser.parseString(body).asJsonObject["messages"].asJsonArray[1].asJsonObject["content"].asString

    @Test
    fun `a term not to translate is masked before sending and restored as written`() {
        val transport = Recorder { body ->
            // The engine moves the masked term: a variable may move.
            chat(userText(body).replace("""Welcome to <x id="1"/>, <x id="0"/>""", """<x id="0"/>, bienvenue sur <x id="1"/>"""))
        }
        val engine = preset("openai-compatible").copy(glossary = glossary)

        val result = HttpTranslationProvider(engine, "", transport)
            .translate(TranslationRequest(listOf("Welcome to Acme Cloud, {{name}}"), "en", "fr"))

        assertEquals("""Welcome to <x id="1"/>, <x id="0"/>""", userText(transport.bodies.single()), "the term never leaves")
        assertEquals(listOf(Translation.Done("{{name}}, bienvenue sur Acme Cloud")), result)
    }

    @Test
    fun `a term is kept by DeepL too, and matched as a whole word only`() {
        val transport = Recorder { body ->
            val text = JsonParser.parseString(body).asJsonObject["text"].asJsonArray[0].asString
            """{"translations":[{"text":${com.google.gson.Gson().toJson(text.replace("Open", "Ouvrir"))}}]}"""
        }
        val engine = preset("deepl").copy(glossary = Glossary(listOf(Glossary.Term("Acme"))))

        val result = HttpTranslationProvider(engine, "k", transport)
            .translate(TranslationRequest(listOf("Open Acme, not Acmeville"), "en", "fr"))

        val sent = JsonParser.parseString(transport.bodies.single()).asJsonObject["text"].asJsonArray[0].asString
        assertEquals("""Open <x id="0"/>, not Acmeville""", sent)
        assertEquals(listOf(Translation.Done("Ouvrir Acme, not Acmeville")), result)
    }

    @Test
    fun `a term lost by the engine is refused like a lost placeholder`() {
        val transport = Recorder { chat("Bienvenue") }
        val engine = preset("openai-compatible").copy(glossary = glossary)

        val result = HttpTranslationProvider(engine, "", transport).translate(TranslationRequest(listOf("Welcome to Acme Cloud"), "en", "fr"))

        assertEquals(listOf(Translation.Failed(PluginBundle.message("translate.error.placeholders", engine.name))), result)
    }

    @Test
    fun `the LLM prompt holds the glossary of the target locale only`() {
        val transport = Recorder { chat("Votre panier") }
        val engine = preset("openai-compatible").copy(glossary = glossary)

        HttpTranslationProvider(engine, "", transport).translate(TranslationRequest(listOf("Your cart"), "en", "fr"))

        val prompt = systemPrompt(transport.bodies.single())
        assertTrue(prompt.endsWith("""reply with the translation only. Use this glossary, each term with its translation: "cart" → "panier"; "checkout" → "paiement"."""), prompt)
        assertFalse("Warenkorb" in prompt, "another locale's translation stays out")
        assertFalse("Acme Cloud" in prompt, "a kept term is masked, not explained")
    }

    @Test
    fun `the batch prompt holds the glossary too`() {
        val transport = Recorder { chat("""["Votre panier","Paiement"]""") }
        val engine = preset("openai-compatible").copy(glossary = glossary)

        val result = HttpTranslationProvider(engine, "", transport).translate(TranslationRequest(listOf("Your cart", "Checkout"), "en", "fr"))

        assertEquals(listOf(Translation.Done("Votre panier"), Translation.Done("Paiement")), result)
        assertTrue(""""cart" → "panier"""" in systemPrompt(transport.bodies.single()))
    }

    @Test
    fun `a target rewritten for its engine still finds its translations`() {
        assertEquals("panier", glossary.terms[1].translationFor("FR"))
        assertEquals("paiement", glossary.terms[2].translationFor("fr"), "fr_FR serves fr")
        assertEquals("Warenkorb", glossary.terms[1].translationFor("DE-CH"))
        assertEquals(null, glossary.terms[1].translationFor("es"))
    }

    /** The preset bodies as they were before the glossary: the same request must go out. */
    private val promptBefore = """{"model": "llama3.1", "temperature": 0, "messages": [{"role": "system", "content": "Translate the user's text from {source} to {target}. It is a UI string of an application; context: {context}. Keep every <x id=\"N\"/> tag exactly as written, and reply with the translation only."}, {"role": "user", "content": "{text}"}]}"""

    private fun before(target: String) = HttpTranslationProvider.fill(
        promptBefore, mapOf("text" to "Hello", "source" to "en", "target" to target, "context" to "ctx")
    ) { HttpTranslationProvider.escapeJson(it) }

    @Test
    fun `without a glossary the request is the same as before`() {
        val withoutGlossary = Recorder { chat("Bonjour") }
        HttpTranslationProvider(preset("openai-compatible"), "", withoutGlossary).translate(TranslationRequest(listOf("Hello"), "en", "fr", "ctx"))
        assertEquals(before("fr"), withoutGlossary.bodies.single())

        val noTermForTheLocale = Recorder { chat("Hola") }
        HttpTranslationProvider(preset("openai-compatible").copy(glossary = glossary), "", noTermForTheLocale)
            .translate(TranslationRequest(listOf("Hello"), "en", "es", "ctx"))
        assertEquals(before("es"), noTermForTheLocale.bodies.single(), "no term for the locale: no glossary sentence")
    }

    @Test
    fun `DeepL's request is unchanged by translated terms`() {
        val plain = Recorder { """{"translations":[{"text":"Votre panier"}]}""" }
        val withGlossary = Recorder { """{"translations":[{"text":"Votre panier"}]}""" }
        val request = TranslationRequest(listOf("Your cart"), "en", "fr")

        HttpTranslationProvider(preset("deepl"), "k", plain).translate(request)
        HttpTranslationProvider(preset("deepl").copy(glossary = glossary), "k", withGlossary).translate(request)

        assertEquals(plain.bodies, withGlossary.bodies)
    }

    @Test
    fun `an entry saved before the glossary sends today's prompt and keeps its batch form`() {
        val llm = preset("openai-compatible")
        val saved = EngineState.of(llm).copy(body = promptBefore)

        val config = saved.toConfig(glossary)

        assertEquals(llm.body, config.body)
        assertEquals(llm.batch, config.batch)
        assertSame(glossary, config.glossary)
    }

    @Test
    fun `the glossary is written one term per line and read back`() {
        val text = "Acme Cloud\ncart = fr: panier; de: Warenkorb\n\n = fr: orphan\nbroken = fr"
        val terms = GlossaryTerm.parse(text)

        assertEquals(listOf(
            GlossaryTerm("Acme Cloud"),
            GlossaryTerm("cart", linkedMapOf("fr" to "panier", "de" to "Warenkorb")),
            GlossaryTerm("broken")
        ), terms)
        assertEquals("Acme Cloud\ncart = fr: panier; de: Warenkorb\nbroken", GlossaryTerm.format(terms))
    }

    @Test
    fun `the glossary is persisted with the translation settings`() {
        val settings = MachineTranslationSettings.Settings(
            enabled = true,
            glossary = mutableListOf(GlossaryTerm("Acme"), GlossaryTerm("cart", linkedMapOf("fr" to "panier", "pt_BR" to "carrinho")))
        )
        val xml = JDOMUtil.write(XmlSerializer.serialize(settings))
        val loaded = XmlSerializer.deserialize(JDOMUtil.load(xml), MachineTranslationSettings.Settings::class.java)

        assertEquals(settings, loaded)
        val service = MachineTranslationSettings().apply { loadState(loaded) }
        assertEquals(settings.glossary, service.copy().glossary)
    }

    @Test
    fun `the provider of the settings applies the glossary`() {
        val engine = EngineState.of(preset("openai-compatible"))
        val service = MachineTranslationSettings().apply {
            loadState(MachineTranslationSettings.Settings(true, mutableListOf(engine), mutableListOf(GlossaryTerm("cart", linkedMapOf("fr" to "panier")))))
        }
        val transport = Recorder { chat("Panier") }
        val keys = object : ApiKeyStore {
            override fun get(engineId: String) = ""
            override fun set(engineId: String, key: String) = Unit
        }

        service.provider(keys, transport)!!.translate(TranslationRequest(listOf("Cart"), "en", "fr"))

        assertTrue(""""cart" → "panier"""" in systemPrompt(transport.bodies.single()))
    }

    private fun find(root: Component, name: String): Component? {
        if (root.name == name) return root
        return (root as? Container)?.components?.firstNotNullOfOrNull { find(it, name) }
    }

    @Test
    fun `the settings page edits the glossary of the copy`() {
        val copy = MachineTranslationSettings.Settings(enabled = true, glossary = mutableListOf(GlossaryTerm("Acme")))
        val panel = MachineTranslationPanel(copy, { "" }) { _, _, _ -> }
        val area = find(panel, PluginBundle.message("settings.translate.glossary")) as javax.swing.JTextArea

        assertEquals("Acme", area.text)
        area.text = "Acme\ncart = fr: panier"

        assertEquals(listOf(GlossaryTerm("Acme"), GlossaryTerm("cart", linkedMapOf("fr" to "panier"))), copy.glossary)
    }
}
