package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.ide.dialog.MachineTranslationPreviewDialog
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Each proposal says which engine translated it, so a fallback from the local engine to a remote
 * one is visible in the preview. Engines are configured here, not taken from the presets, and a
 * fake transport answers: no test reaches the network.
 */
class EngineAttributionTest {

    private val ollama = engine("ollama", "Ollama", local = true)
    private val deepl = engine("deepl", "DeepL", local = false)

    private fun engine(id: String, name: String, local: Boolean) = EngineConfig(
        id = id,
        name = name,
        url = "https://$id.test/translate",
        headers = emptyMap(),
        body = """{"text":"{text}"}""",
        responsePath = "text",
        local = local
    )

    /** Answers each engine by its URL: [down] engines fail, the others translate. */
    private fun transport(vararg down: EngineConfig) = HttpTransport { url, _, body ->
        if (down.any { url == it.url }) HttpTransport.Response(500, "unavailable")
        else HttpTransport.Response(200, """{"text":"FR ${body.substringAfter("\"text\":\"").substringBefore("\"")}"}""")
    }

    private val request = TranslationRequest(listOf("Hello", "Bye"), "en", "fr")

    private fun translate(transport: HttpTransport): List<Translation> =
        TranslationEngines.provider(listOf(ollama, deepl), { "key" }, transport).translate(request)

    @Test
    fun `a proposal from the local engine says so`() {
        val results = translate(transport())

        assertEquals(listOf("FR Hello", "FR Bye"), results.map { (it as Translation.Done).text })
        results.forEach { assertEquals(TranslatedBy("Ollama", local = true), (it as Translation.Done).engine) }
    }

    @Test
    fun `after a fallback the proposal names the second engine, not local`() {
        val results = translate(transport(ollama))

        assertEquals(listOf("FR Hello", "FR Bye"), results.map { (it as Translation.Done).text })
        results.forEach { assertEquals(TranslatedBy("DeepL", local = false), (it as Translation.Done).engine) }
    }

    @Test
    fun `a blank text no engine saw has no engine`() {
        val result = HttpTranslationProvider(deepl, "key", transport()).translate(request.copy(texts = listOf(" "))).single()

        assertNull((result as Translation.Done).engine)
    }

    @Test
    fun `the engine does not change what a translation is`() {
        assertEquals(Translation.Done("Bonjour"), Translation.Done("Bonjour", TranslatedBy("DeepL", local = false)))
    }

    @Test
    fun `the preview column flags a local engine`() {
        assertEquals(PluginBundle.message("action.fill.engine.local", "Ollama"), MachineTranslationPreviewDialog.engineLabel(TranslatedBy("Ollama", local = true)))
        assertEquals("DeepL", MachineTranslationPreviewDialog.engineLabel(TranslatedBy("DeepL", local = false)))
    }
}
