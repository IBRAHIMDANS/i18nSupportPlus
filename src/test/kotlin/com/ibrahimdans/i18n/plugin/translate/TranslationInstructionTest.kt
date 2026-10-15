package com.ibrahimdans.i18n.plugin.translate

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The instruction typed in the key's dialog (a tone, a length): it reaches the prompt of the LLM
 * engines only, and a request without one is the same as before.
 */
class TranslationInstructionTest {

    private class Recorder(private val answer: String) : HttpTransport {
        val bodies = mutableListOf<String>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
            bodies += body
            return HttpTransport.Response(200, answer)
        }
    }

    private fun preset(id: String) = TranslationEngines.preset(id)!!

    private fun chat(content: String) = """{"choices":[{"message":{"content":${Gson().toJson(content)}}}]}"""

    private fun systemPrompt(body: String) =
        JsonParser.parseString(body).asJsonObject["messages"].asJsonArray[0].asJsonObject["content"].asString

    @Test
    fun `the LLM prompt ends with the instruction`() {
        val transport = Recorder(chat("Salut"))

        HttpTranslationProvider(preset("openai-compatible"), "", transport)
            .translate(TranslationRequest(listOf("Hello"), "en", "fr", instruction = " informal, short. "))

        val prompt = systemPrompt(transport.bodies.single())
        assertTrue(prompt.endsWith("reply with the translation only. Also follow this instruction from the user: informal, short."), prompt)
    }

    @Test
    fun `the batch prompt holds the instruction too`() {
        val transport = Recorder(chat("""["Salut","Ciao"]"""))

        val result = HttpTranslationProvider(preset("openai-compatible"), "", transport)
            .translate(TranslationRequest(listOf("Hello", "Bye"), "en", "fr", instruction = "informal"))

        assertEquals(listOf(Translation.Done("Salut"), Translation.Done("Ciao")), result)
        assertTrue("Also follow this instruction from the user: informal." in systemPrompt(transport.bodies.single()))
    }

    @Test
    fun `a blank instruction leaves the prompt as it was`() {
        assertEquals("", HttpTranslationProvider.instructionPrompt("  "))
        val without = Recorder(chat("Bonjour"))
        val blank = Recorder(chat("Bonjour"))

        HttpTranslationProvider(preset("openai-compatible"), "", without).translate(TranslationRequest(listOf("Hello"), "en", "fr"))
        HttpTranslationProvider(preset("openai-compatible"), "", blank).translate(TranslationRequest(listOf("Hello"), "en", "fr", instruction = " "))

        assertEquals(without.bodies, blank.bodies)
        assertFalse("{instruction}" in without.bodies.single(), "the placeholder never goes out")
    }

    @Test
    fun `DeepL's request is unchanged by an instruction`() {
        val plain = Recorder("""{"translations":[{"text":"Bonjour"}]}""")
        val instructed = Recorder("""{"translations":[{"text":"Bonjour"}]}""")

        HttpTranslationProvider(preset("deepl"), "k", plain).translate(TranslationRequest(listOf("Hello"), "en", "fr"))
        HttpTranslationProvider(preset("deepl"), "k", instructed).translate(TranslationRequest(listOf("Hello"), "en", "fr", instruction = "formal"))

        assertEquals(plain.bodies, instructed.bodies)
    }

    @Test
    fun `an entry saved before the instruction sends today's prompt and keeps its batch form`() {
        val llm = preset("openai-compatible")
        val saved = EngineState.of(llm).copy(body = llm.body.replace("{instruction}", ""))

        val config = saved.toConfig()

        assertEquals(llm.body, config.body)
        assertEquals(llm.batch, config.batch)
    }

    @Test
    fun `only an LLM engine of a project that opted in reads the instruction`() {
        fun settings(enabled: Boolean, vararg presets: String) = MachineTranslationSettings().apply {
            loadState(MachineTranslationSettings.Settings(enabled, presets.map { EngineState.of(preset(it)) }.toMutableList()))
        }

        assertTrue(settings(true, "deepl", "openai-compatible").readsInstruction())
        assertFalse(settings(true, "deepl", "google-cloud", "libretranslate").readsInstruction())
        assertFalse(settings(false, "openai-compatible").readsInstruction(), "machine translation off")
        assertTrue(
            settings(true, "openai-compatible").apply { state.engines[0].body = preset("openai-compatible").body.replace("{instruction}", "") }
                .readsInstruction(),
            "an entry saved before the instruction sends today's prompt"
        )
    }
}
