package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.ide.dialog.MachineTranslationPreviewDialog
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** After a fallback, each proposal says which engine made it, and whether it ran locally. */
class EngineAttributionTest {

    private val ollama = TranslationEngines.preset("openai-compatible")!!.copy(name = "Ollama")
    private val deepl = TranslationEngines.preset("deepl")!!.copy(name = "DeepL")

    private val transport = HttpTransport { url, _, _ ->
        if ("deepl" in url) HttpTransport.Response(200, """{"translations":[{"text":"Bonjour"}]}""")
        else HttpTransport.Response(503, "")
    }

    private fun translate(vararg texts: String) =
        TranslationEngines.provider(listOf(ollama, deepl), { "k" }, transport).translate(TranslationRequest(texts.toList(), "en", "fr"))

    @Test
    fun `a fallback proposal names the second engine`() {
        val done = translate("Hello").single() as Translation.Done

        assertEquals("Bonjour", done.text)
        assertEquals("DeepL", done.engine)
        assertFalse(done.local)
    }

    @Test
    fun `a local engine is flagged`() {
        val local = HttpTransport { _, _, _ -> HttpTransport.Response(200, """{"choices":[{"message":{"content":"Salut"}}]}""") }
        val done = HttpTranslationProvider(ollama, "", local).translate(TranslationRequest(listOf("Hi"), "en", "fr")).single() as Translation.Done

        assertEquals("Ollama", done.engine)
        assertTrue(done.local)
        assertEquals(PluginBundle.message("action.fill.engine.local", "Ollama"), MachineTranslationPreviewDialog.engineOf(done))
    }

    @Test
    fun `an empty text was sent to no engine`() {
        assertEquals("", (translate(" ").single() as Translation.Done).engine)
    }

    @Test
    fun `the preview shows the engine of a proposal and nothing for a failure`() {
        assertEquals("DeepL", MachineTranslationPreviewDialog.engineOf(translate("Hello").single()))
        assertEquals("", MachineTranslationPreviewDialog.engineOf(Translation.Failed("no")))
    }
}
