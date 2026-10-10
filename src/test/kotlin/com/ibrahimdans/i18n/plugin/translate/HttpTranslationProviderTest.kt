package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * One configurable HTTP provider stands for every engine. A fake transport records the request
 * and answers it: no test reaches the network.
 */
class HttpTranslationProviderTest {

    private class FakeTransport(private val answer: (String) -> HttpTransport.Response) : HttpTransport {
        val calls = mutableListOf<Triple<String, Map<String, String>, String>>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
            calls += Triple(url, headers, body)
            return answer(body)
        }
    }

    private val request = TranslationRequest(listOf("Hello {{name}}"), "en", "fr", "key greeting.hello")

    private fun ok(json: String) = HttpTransport.Response(200, json)

    private fun preset(id: String) = TranslationEngines.preset(id)!!

    private fun translate(engine: EngineConfig, transport: HttpTransport, key: String = "secret") =
        HttpTranslationProvider(engine, key, transport).translate(request)

    @Test
    fun `DeepL gets the masked text, the languages, the context and its key header`() {
        val transport = FakeTransport { ok("""{"translations":[{"text":"Bonjour <x id=\"0\"/>"}]}""") }

        assertEquals(listOf(Translation.Done("Bonjour {{name}}")), translate(preset("deepl"), transport))

        val (url, headers, body) = transport.calls.single()
        assertEquals("https://api-free.deepl.com/v2/translate", url)
        assertEquals("DeepL-Auth-Key secret", headers["Authorization"])
        val json = JsonParser.parseString(body).asJsonObject
        assertEquals("""Hello <x id="0"/>""", json["text"].asJsonArray[0].asString, "the variable never leaves masked")
        assertEquals("en", json["source_lang"].asString)
        assertEquals("fr", json["target_lang"].asString)
        assertEquals("xml", json["tag_handling"].asString)
        assertEquals("key greeting.hello", json["context"].asString)
    }

    @Test
    fun `an OpenAI-compatible endpoint gets a prompt and is read from the chat answer`() {
        val transport = FakeTransport { ok("""{"choices":[{"message":{"content":"  Bonjour <x id=\"0\"/>\n"}}]}""") }

        assertEquals(listOf(Translation.Done("Bonjour {{name}}")), translate(preset("openai-compatible"), transport, key = ""))

        val (url, _, body) = transport.calls.single()
        assertEquals("http://localhost:11434/v1/chat/completions", url, "a local Ollama by default")
        val messages = JsonParser.parseString(body).asJsonObject["messages"].asJsonArray
        val system = messages[0].asJsonObject["content"].asString
        assertTrue("from en to fr" in system && "key greeting.hello" in system && "<x id=" in system, system)
        assertEquals("""Hello <x id="0"/>""", messages[1].asJsonObject["content"].asString)
    }

    @Test
    fun `LibreTranslate and Google answers are decoded from HTML`() {
        val libre = FakeTransport { ok("""{"translatedText":"L&#39;ami <x id=\"0\"/> &amp; co"}""") }
        assertEquals(listOf(Translation.Done("L'ami {{name}} & co")), translate(preset("libretranslate"), libre))

        val google = FakeTransport { ok("""{"data":{"translations":[{"translatedText":"Bonjour <x id=\"0\"/>"}]}}""") }
        assertEquals(listOf(Translation.Done("Bonjour {{name}}")), translate(preset("google-cloud"), google, key = "a b&c"))
        assertEquals("https://translation.googleapis.com/language/translate/v2?key=a+b%26c", google.calls.single().first, "the key is URL-encoded")
    }

    @Test
    fun `quotes and newlines in the text keep the body valid JSON`() {
        val transport = FakeTransport { ok("""{"translations":[{"text":"x"}]}""") }
        HttpTranslationProvider(preset("deepl"), "k", transport).translate(request.copy(texts = listOf("Say \"hi\"\\\nnow")))

        assertEquals("Say \"hi\"\\\nnow", JsonParser.parseString(transport.calls.single().third).asJsonObject["text"].asJsonArray[0].asString)
    }

    @Test
    fun `a lost variable is refused`() {
        val transport = FakeTransport { ok("""{"translations":[{"text":"Bonjour"}]}""") }

        assertEquals(listOf(Translation.Failed(PluginBundle.message("translate.error.placeholders", "DeepL"))), translate(preset("deepl"), transport))
    }

    @Test
    fun `errors are reasons, never exceptions`() {
        fun failure(transport: HttpTransport) = translate(preset("deepl"), transport).single()

        assertEquals(Translation.Failed(PluginBundle.message("translate.error.key", "DeepL")), failure { _, _, _ -> HttpTransport.Response(403, "") })
        assertEquals(Translation.Failed(PluginBundle.message("translate.error.rate", "DeepL")), failure { _, _, _ -> HttpTransport.Response(429, "") })
        assertEquals(Translation.Failed(PluginBundle.message("translate.error.status", "DeepL", 500, "boom")), failure { _, _, _ -> HttpTransport.Response(500, "boom") })
        assertEquals(Translation.Failed(PluginBundle.message("translate.error.timeout", "DeepL")), failure { _, _, _ -> throw SocketTimeoutException() })
        assertEquals(Translation.Failed(PluginBundle.message("translate.error.network", "DeepL", "refused")), failure { _, _, _ -> throw IOException("refused") })
        assertEquals(
            Translation.Failed(PluginBundle.message("translate.error.response", "DeepL", "translations[0].text")),
            failure { _, _, _ -> ok("""{"message":"quota"}""") }
        )
    }

    @Test
    fun `a blank text is not sent`() {
        val transport = FakeTransport { error("no call expected") }

        assertEquals(listOf(Translation.Done(" ")), HttpTranslationProvider(preset("deepl"), "k", transport).translate(request.copy(texts = listOf(" "))))
    }

    @Test
    fun `the fallback asks the next engine only for what failed`() {
        val first = FakeTransport { body -> if ("one" in body) ok("""{"translations":[{"text":"un"}]}""") else HttpTransport.Response(503, "") }
        val second = FakeTransport { ok("""{"translations":[{"text":"deux"}]}""") }
        val provider = FallbackTranslationProvider(listOf(
            HttpTranslationProvider(preset("deepl"), "k", first),
            HttpTranslationProvider(preset("deepl"), "k", second)
        ))

        assertEquals(listOf(Translation.Done("un"), Translation.Done("deux")), provider.translate(request.copy(texts = listOf("one", "two"))))
        assertEquals(1, second.calls.size, "only the failed text goes to the second engine")
    }

    @Test
    fun `every preset is complete`() {
        assertEquals(listOf("openai-compatible", "deepl", "libretranslate", "google-cloud"), TranslationEngines.presets.map { it.id })
        TranslationEngines.presets.forEach {
            assertTrue(it.name.isNotBlank() && it.url.startsWith("http") && it.responsePath.isNotBlank(), it.id)
            assertTrue("{text}" in it.body, "${it.id} never sends the text")
        }
    }
}
