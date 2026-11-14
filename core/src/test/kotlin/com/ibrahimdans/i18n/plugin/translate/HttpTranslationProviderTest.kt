package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.utils.CoreMessages
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions
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

    /** A provider that tries a request again without waiting: a rate limit or a 503 costs no test any time. */
    private fun provider(engine: EngineConfig, key: String, transport: HttpTransport) =
        HttpTranslationProvider(engine, key, transport).apply { pause = Pause { _, _ -> } }

    private fun translate(engine: EngineConfig, transport: HttpTransport, key: String = "secret") =
        provider(engine, key, transport).translate(request)

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
        val (url, headers, _) = google.calls.single()
        assertEquals("https://translation.googleapis.com/language/translate/v2", url, "the key never goes in the URL")
        assertEquals("a b&c", headers["X-goog-api-key"])
    }

    @Test
    fun `Anthropic gets the native Messages request, thinking off and no sampling parameter`() {
        val transport = FakeTransport { ok("""{"content":[{"type":"text","text":"Bonjour <x id=\"0\"/>"}],"stop_reason":"end_turn"}""") }

        assertEquals(listOf(Translation.Done("Bonjour {{name}}")), translate(preset("anthropic"), transport))

        val (url, headers, body) = transport.calls.single()
        assertEquals("https://api.anthropic.com/v1/messages", url)
        assertEquals(mapOf("x-api-key" to "secret", "anthropic-version" to "2026-10-21"), headers)
        val json = JsonParser.parseString(body).asJsonObject
        assertEquals("claude-haiku-5-5", json["model"].asString)
        assertEquals("disabled", json["thinking"].asJsonObject["type"].asString, "the translation must be the first content block")
        assertTrue(listOf("temperature", "top_p", "top_k").none(json::has), "API version 2026-10-21 rejects them")
        val system = json["system"].asString
        assertTrue("from en to fr" in system && "key greeting.hello" in system && "<x id=" in system, system)
        assertEquals("""Hello <x id="0"/>""", json["messages"].asJsonArray.single().asJsonObject["content"].asString)
    }

    @Test
    fun `Anthropic answers a batch with a JSON array in its text block`() {
        val transport = FakeTransport { body ->
            val json = JsonParser.parseString(body).asJsonObject
            val sent = JsonParser.parseString(json["messages"].asJsonArray.single().asJsonObject["content"].asString).asJsonArray.map { it.asString }
            assertEquals(listOf("""Hi <x id="0"/>""", "Bye"), sent, "the array travels as the user message")
            assertEquals("disabled", json["thinking"].asJsonObject["type"].asString)
            ok("""{"content":[{"type":"text","text":"[\"Salut <x id=\\\"0\\\"/>\", \"Au revoir\"]"}]}""")
        }

        val result = provider(preset("anthropic"), "k", transport).translate(request.copy(texts = listOf("Hi {{name}}", "Bye")))

        assertEquals(listOf(Translation.Done("Salut {{name}}"), Translation.Done("Au revoir")), result)
        assertEquals(1, transport.calls.size, "both texts in one request")
    }

    @Test
    fun `Gemini goes through its OpenAI-compatible endpoint with a Bearer key`() {
        val transport = FakeTransport { ok("""{"choices":[{"message":{"role":"assistant","content":"Bonjour <x id=\"0\"/>"}}]}""") }

        assertEquals(listOf(Translation.Done("Bonjour {{name}}")), translate(preset("gemini"), transport))

        val (url, headers, body) = transport.calls.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", url, "the key never goes in the URL")
        assertEquals(mapOf("Authorization" to "Bearer secret"), headers)
        val json = JsonParser.parseString(body).asJsonObject
        assertEquals("gemini-4-hydrogen", json["model"].asString)
        assertEquals("low", json["reasoning_effort"].asString)
        val messages = json["messages"].asJsonArray
        assertEquals("system", messages[0].asJsonObject["role"].asString)
        assertTrue("from en to fr" in messages[0].asJsonObject["content"].asString)
        assertEquals("""Hello <x id="0"/>""", messages[1].asJsonObject["content"].asString)
    }

    @Test
    fun `quotes and newlines in the text keep the body valid JSON`() {
        val transport = FakeTransport { ok("""{"translations":[{"text":"x"}]}""") }
        provider(preset("deepl"), "k", transport).translate(request.copy(texts = listOf("Say \"hi\"\\\nnow")))

        assertEquals("Say \"hi\"\\\nnow", JsonParser.parseString(transport.calls.single().third).asJsonObject["text"].asJsonArray[0].asString)
    }

    @Test
    fun `a lost variable is refused`() {
        val transport = FakeTransport { ok("""{"translations":[{"text":"Bonjour"}]}""") }

        assertEquals(listOf(Translation.Failed(CoreMessages.message("translate.error.placeholders", "DeepL"))), translate(preset("deepl"), transport))
    }

    @Test
    fun `errors are reasons, never exceptions`() {
        fun failure(transport: HttpTransport) = translate(preset("deepl"), transport).single()

        assertEquals(Translation.Failed(CoreMessages.message("translate.error.key", "DeepL")), failure { _, _, _ -> HttpTransport.Response(403, "") })
        assertEquals(Translation.Failed(CoreMessages.message("translate.error.rate", "DeepL")), failure { _, _, _ -> HttpTransport.Response(429, "") })
        assertEquals(Translation.Failed(CoreMessages.message("translate.error.status", "DeepL", 500, "boom")), failure { _, _, _ -> HttpTransport.Response(500, "boom") })
        assertEquals(Translation.Failed(CoreMessages.message("translate.error.timeout", "DeepL")), failure { _, _, _ -> throw SocketTimeoutException() })
        assertEquals(Translation.Failed(CoreMessages.message("translate.error.network", "DeepL", "refused")), failure { _, _, _ -> throw IOException("refused") })
        assertEquals(
            Translation.Failed(CoreMessages.message("translate.error.response", "DeepL", "translations[0].text")),
            failure { _, _, _ -> ok("""{"message":"quota"}""") }
        )
    }

    @Test
    fun `a blank text is not sent`() {
        val transport = FakeTransport { error("no call expected") }

        assertEquals(listOf(Translation.Done(" ")), provider(preset("deepl"), "k", transport).translate(request.copy(texts = listOf(" "))))
    }

    @Test
    fun `the fallback asks the next engine only for what failed`() {
        val first = FakeTransport { body -> if ("one" in body) ok("""{"translations":[{"text":"un"}]}""") else HttpTransport.Response(503, "") }
        val second = FakeTransport { ok("""{"translations":[{"text":"deux"}]}""") }
        val fallback = FallbackTranslationProvider(listOf(
            provider(preset("deepl"), "k", first),
            provider(preset("deepl"), "k", second)
        ))

        assertEquals(listOf(Translation.Done("un"), Translation.Done("deux")), fallback.translate(request.copy(texts = listOf("one", "two"))))
        assertEquals(1, second.calls.size, "only the failed text goes to the second engine")
    }

    private class Cancelled : RuntimeException()

    @Test
    fun `a cancellation stops between two texts`() {
        val transport = FakeTransport { ok("""{"translations":[{"text":"un"}]}""") }
        var checks = 0
        val cancelOnSecond = Cancellation { if (++checks == 2) throw Cancelled() }

        Assertions.assertThrows(Cancelled::class.java) {
            provider(preset("deepl").copy(batch = null), "k", transport).translate(request.copy(texts = listOf("one", "two", "three")), cancelOnSecond)
        }
        assertEquals(1, transport.calls.size, "the second text is never sent")
    }

    private fun deeplAnswer(body: String): HttpTransport.Response {
        val texts = JsonParser.parseString(body).asJsonObject["text"].asJsonArray.map { it.asString }
        return ok(texts.joinToString(",", """{"translations":[""", "]}") { """{"text":"FR ${it.replace("\"", "\\\"")}"}""" })
    }

    @Test
    fun `several texts go to DeepL in one request, in order`() {
        val transport = FakeTransport(::deeplAnswer)

        val result = provider(preset("deepl"), "k", transport)
            .translate(request.copy(texts = listOf("one {{n}}", "two", "three")))

        Assertions.assertEquals(listOf(Translation.Done("FR one {{n}}"), Translation.Done("FR two"), Translation.Done("FR three")), result)
        val sent = JsonParser.parseString(transport.calls.single().third).asJsonObject["text"].asJsonArray.map { it.asString }
        Assertions.assertEquals(listOf("""one <x id="0"/>""", "two", "three"), sent, "masked, in order")
    }

    @Test
    fun `a batch is cut at the preset's size`() {
        val engine = preset("deepl").let { it.copy(batch = it.batch!!.copy(maxSize = 2)) }
        val transport = FakeTransport(::deeplAnswer)

        val result = provider(engine, "k", transport).translate(request.copy(texts = listOf("a", "b", "c", "d", "e")))

        Assertions.assertEquals(listOf("FR a", "FR b", "FR c", "FR d", "FR e"), result.map { (it as Translation.Done).text })
        Assertions.assertEquals(listOf(2, 2, 1), transport.calls.map { call ->
            JsonParser.parseString(call.third).asJsonObject["text"].asJsonArray.size()
        })
    }

    @Test
    fun `an answer short of a translation is retried text by text`() {
        val transport = FakeTransport { body ->
            val texts = JsonParser.parseString(body).asJsonObject["text"].asJsonArray
            if (texts.size() > 1) ok("""{"translations":[{"text":"FR a"}]}""") else deeplAnswer(body)
        }

        val result = provider(preset("deepl"), "k", transport).translate(request.copy(texts = listOf("a", "b")))

        Assertions.assertEquals(listOf(Translation.Done("FR a"), Translation.Done("FR b")), result)
        Assertions.assertEquals(3, transport.calls.size, "one batch, then one request per text")
    }

    @Test
    fun `a rate limit on a batch fails its texts once the attempts are spent, without splitting it`() {
        val transport = FakeTransport { HttpTransport.Response(429, "") }

        val result = provider(preset("deepl"), "k", transport).translate(request.copy(texts = listOf("a", "b")))

        Assertions.assertEquals(List(2) { Translation.Failed(CoreMessages.message("translate.error.rate", "DeepL")) }, result)
        Assertions.assertEquals(3, transport.calls.size, "three attempts at the whole batch")
    }

    @Test
    fun `an LLM answers a batch with a JSON array, fenced or not`() {
        val transport = FakeTransport { body ->
            val messages = JsonParser.parseString(body).asJsonObject["messages"].asJsonArray
            val sent = JsonParser.parseString(messages[1].asJsonObject["content"].asString).asJsonArray.map { it.asString }
            Assertions.assertEquals(listOf("""Hi <x id="0"/>""", "Bye"), sent, "the array travels as the user message")
            ok("""{"choices":[{"message":{"content":"```json\n[\"Salut <x id=\\\"0\\\"/>\", \"Au revoir\"]\n```"}}]}""")
        }

        val result = provider(preset("openai-compatible"), "", transport).translate(request.copy(texts = listOf("Hi {{name}}", "Bye")))

        Assertions.assertEquals(listOf(Translation.Done("Salut {{name}}"), Translation.Done("Au revoir")), result)
    }

    @Test
    fun `a blank text in a batch is kept and not sent`() {
        val transport = FakeTransport(::deeplAnswer)

        val result = provider(preset("deepl"), "k", transport).translate(request.copy(texts = listOf("a", " ", "b")))

        Assertions.assertEquals(listOf(Translation.Done("FR a"), Translation.Done(" "), Translation.Done("FR b")), result)
        Assertions.assertEquals(2, JsonParser.parseString(transport.calls.single().third).asJsonObject["text"].asJsonArray.size())
    }

    @Test
    fun `an engine without a batch form sends one request per text`() {
        val transport = FakeTransport { ok("""{"translatedText":"x"}""") }

        provider(preset("libretranslate"), "k", transport).translate(request.copy(texts = listOf("a", "b", "c")))

        Assertions.assertEquals(3, transport.calls.size)
    }

    @Test
    fun `every preset is complete`() {
        assertEquals(listOf("openai-compatible", "deepl", "libretranslate", "google-cloud", "anthropic", "gemini"), TranslationEngines.presets.map { it.id })
        TranslationEngines.presets.forEach {
            assertTrue(it.name.isNotBlank() && it.url.startsWith("http") && it.responsePath.isNotBlank(), it.id)
            assertTrue("{text}" in it.body, "${it.id} never sends the text")
        }
    }

    // ── Language codes ────────────────────────────────────────────────────────

    private fun sentLanguages(engine: EngineConfig, source: String, target: String, sourceField: String, targetField: String): Pair<String, String> {
        val transport = FakeTransport { ok("""{"translations":[{"text":"x"}],"data":{"translations":[{"translatedText":"x"}]}}""") }
        TranslationEngines.provider(listOf(engine), { "secret" }, transport).translate(TranslationRequest(listOf("Hello"), source, target))
        val json = JsonParser.parseString(transport.calls.single().third).asJsonObject
        return json[sourceField].asString to json[targetField].asString
    }

    private fun deepl(source: String, target: String) = sentLanguages(preset("deepl"), source, target, "source_lang", "target_lang")

    private fun google(source: String, target: String) = sentLanguages(preset("google-cloud"), source, target, "source", "target")

    @Test
    fun `DeepL gets an upper-case source without region and a target with its variant`() {
        assertEquals("FR" to "EN-US", deepl("fr", "en"))
        assertEquals("EN" to "EN-GB", deepl("en_US", "en-gb"))
        assertEquals("EN" to "PT-BR", deepl("en", "pt_BR"))
        assertEquals("EN" to "PT-BR", deepl("en", "pt"))
        assertEquals("PT" to "PT-PT", deepl("pt-BR", "pt_PT"))
        assertEquals("EN" to "DE", deepl("en", "de"))
    }

    @Test
    fun `DeepL gets Chinese by script and a region it lacks as the language`() {
        assertEquals("ZH" to "ZH-HANS", deepl("zh_CN", "zh"))
        assertEquals("EN" to "ZH-HANT", deepl("en", "zh_TW"))
        assertEquals("EN" to "ZH-HANT", deepl("en", "zh-Hant"))
        assertEquals("EN" to "FR", deepl("en", "fr_BE"))
    }

    @Test
    fun `Google gets BCP-47 codes`() {
        assertEquals("en" to "zh-CN", google("en", "zh_CN"))
        assertEquals("pt-BR" to "zh-Hant", google("PT_br", "zh_hant"))
        assertEquals("en" to "fr", google("en", "fr"))
    }

    @Test
    fun `an engine whose body was rewritten keeps the project's labels`() {
        val custom = preset("deepl").copy(body = preset("deepl").body.replace("\"formality\": \"{formality}\"", "\"formality\": \"less\""))
        assertEquals("pt_BR" to "en", sentLanguages(custom, "pt_BR", "en", "source_lang", "target_lang"))
    }

    @Test
    fun `DeepL Pro keeps the conversion`() {
        val pro = preset("deepl").copy(url = "https://api.deepl.com/v2/translate")
        assertEquals("EN" to "EN-US", sentLanguages(pro, "en", "en", "source_lang", "target_lang"))
    }

    @Test
    fun `an LLM gets the project's labels`() {
        assertEquals(null, TranslationEngines.languageCodes(preset("openai-compatible")))
    }
}
