package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.utils.CoreMessages
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DeepL gets the glossary's translated terms as a glossary of its own. A fake DeepL answers every
 * verb: no test reaches the network.
 */
class DeeplGlossaryTest {

    private data class Call(val method: String, val url: String, val body: String?) {
        val json get() = body?.let { JsonParser.parseString(it).asJsonObject }
    }

    /**
     * DeepL as far as these tests need it: [glossaries] by id, and what each request answers — the
     * statuses and bodies the Free API gave on 2026-10-11. A just [delete]d glossary makes `/v2/translate`
     * answer 500, not 404, for a few minutes; an unknown id is a 404 there.
     */
    private class FakeDeepl(
        val glossaries: MutableMap<String, String> = linkedMapOf(),
        var createStatus: Int = 201,
        val translateStatus: (Call) -> Int = { 200 }
    ) : HttpTransport {
        val calls = mutableListOf<Call>()
        private val deleted = mutableSetOf<String>()
        private var next = 0

        fun delete(id: String) {
            glossaries.remove(id)
            deleted += id
        }

        fun deleteAll() = glossaries.keys.toList().forEach(::delete)

        override fun post(url: String, headers: Map<String, String>, body: String) = request("POST", url, headers, body)

        override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpTransport.Response {
            val call = Call(method, url, body)
            calls += call
            val path = url.substringAfter("deepl.com")
            val glossaryPath = path.removePrefix("/v3/glossaries/").takeIf { it != path }
            return when {
                path == "/v2/translate" -> {
                    val status = translateStatus(call)
                    val id = call.json?.get("glossary_id")?.asString
                    when {
                        id != null && id in deleted -> HttpTransport.Response(500, """{"message":"Internal server error."}""")
                        id != null && id !in glossaries ->
                            HttpTransport.Response(404, """{"message":"Bad request. Reason: Glossary $id not found"}""")
                        status != 200 -> HttpTransport.Response(status, """{"message":"Internal server error."}""")
                        else -> HttpTransport.Response(200, """{"translations":[{"text":"Votre panier","detected_source_language":"EN"}]}""")
                    }
                }
                path == "/v3/glossaries" && method == "GET" ->
                    HttpTransport.Response(200, """{"glossaries":[${glossaries.entries.joinToString(",") { glossary(it.key, it.value) }}]}""")
                path == "/v3/glossaries" && method == "POST" -> {
                    if (createStatus != 201) return HttpTransport.Response(createStatus, """{"message":"Quota exceeded"}""")
                    val id = "g${++next}"
                    glossaries[id] = call.json!!["name"].asString
                    HttpTransport.Response(201, glossary(id, glossaries.getValue(id)))
                }
                glossaryPath != null && method == "GET" && '/' !in glossaryPath ->
                    glossaries[glossaryPath]?.let { HttpTransport.Response(200, glossary(glossaryPath, it)) } ?: NOT_FOUND
                glossaryPath != null && method == "PUT" && glossaryPath.endsWith("/dictionaries") -> {
                    val id = glossaryPath.substringBefore('/')
                    val sent = call.json!!
                    if (id !in glossaries) NOT_FOUND
                    else HttpTransport.Response(200, """{"source_lang":"${sent["source_lang"].asString}","target_lang":"${sent["target_lang"].asString}","entry_count":1}""")
                }
                else -> HttpTransport.Response(500, "unexpected $method $url")
            }
        }

        private fun glossary(id: String, name: String) =
            """{"glossary_id":"$id","name":"$name","dictionaries":[{"source_lang":"en","target_lang":"fr","entry_count":1}],"creation_time":"2026-10-11T09:09:13.44614Z"}"""

        fun glossaryCalls() = calls.filter { "/v3/glossaries" in it.url }
        fun translations() = calls.filter { it.url.endsWith("/v2/translate") }

        companion object {
            val NOT_FOUND = HttpTransport.Response(404, """{"message":"Glossary not found"}""")
        }
    }

    private val glossary = Glossary(listOf(
        Glossary.Term("Acme"),
        Glossary.Term("cart", mapOf("fr" to "panier", "de" to "Warenkorb"))
    ))

    private val store = InMemoryGlossaryStore()
    private val warnings = mutableListOf<String>()

    private fun deepl(terms: Glossary = glossary) =
        TranslationEngines.preset("deepl")!!.copy(id = "engine-1", glossary = terms, remoteGlossary = store)

    /** DeepL as the settings call it: the project's locales rewritten to DeepL's codes first. */
    private fun translate(transport: HttpTransport, target: String = "fr", engine: EngineConfig = deepl(), texts: List<String> = listOf("Your cart")) =
        HttpTranslationProvider(engine, "k", transport).apply { glossaryWarnings = { warnings += it } }
            .translate(TranslationRequest(texts, LanguageCodes.DEEPL.source("en"), LanguageCodes.DEEPL.target(target)))

    @Test
    fun `the glossary is created on the first translation and its id is sent`() {
        val transport = FakeDeepl()

        assertEquals(listOf(Translation.Done("Votre panier")), translate(transport))

        val create = transport.calls.single { it.method == "POST" && it.url.endsWith("/v3/glossaries") }
        assertEquals("https://api-free.deepl.com/v3/glossaries", create.url)
        val dictionary = create.json!!["dictionaries"].asJsonArray.single().asJsonObject
        assertEquals("en", dictionary["source_lang"].asString)
        assertEquals("fr", dictionary["target_lang"].asString)
        assertEquals("cart\tpanier", dictionary["entries"].asString, "a kept term is masked, not sent in the glossary")
        assertEquals("tsv", dictionary["entries_format"].asString)
        val sent = transport.translations().single().json!!
        assertEquals("g1", sent["glossary_id"].asString)
        assertEquals("EN", sent["source_lang"].asString, "glossary_id goes with a source language")
        assertEquals("g1", store.glossaryId, "the id is kept for the engine")
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `the id is reused, and only a changed pair is sent again`() {
        val transport = FakeDeepl()
        translate(transport)
        translate(transport, target = "en_US")
        transport.calls.clear()

        translate(transport)
        assertTrue(transport.glossaryCalls().isEmpty(), "nothing changed: no glossary call")

        translate(transport, engine = deepl(Glossary(listOf(Glossary.Term("cart", mapOf("fr" to "chariot"))))))
        val put = transport.glossaryCalls().single()
        assertEquals("PUT", put.method)
        assertEquals("https://api-free.deepl.com/v3/glossaries/g1/dictionaries", put.url)
        assertEquals("cart\tchariot", put.json!!["entries"].asString)
        assertEquals("g1", transport.translations().last().json!!["glossary_id"].asString)
    }

    @Test
    fun `a pair without a dictionary sends no glossary_id`() {
        val transport = FakeDeepl()

        translate(transport, target = "es")

        assertTrue(transport.glossaryCalls().isEmpty())
        assertFalse("glossary_id" in transport.translations().single().body!!)
    }

    @Test
    fun `a glossary deleted on DeepL's side is recreated`() {
        val transport = FakeDeepl()
        translate(transport)
        transport.deleteAll()
        transport.calls.clear()

        assertEquals(listOf(Translation.Done("Votre panier")), translate(transport))

        assertEquals(listOf("g1", "g2"), transport.translations().map { it.json!!["glossary_id"].asString })
        assertEquals(
            listOf("GET https://api-free.deepl.com/v3/glossaries/g1", "GET https://api-free.deepl.com/v3/glossaries", "POST https://api-free.deepl.com/v3/glossaries"),
            transport.glossaryCalls().map { "${it.method} ${it.url}" },
            "DeepL answers 500 just after a glossary is deleted: its absence is checked before recreating it"
        )
        assertEquals("g2", store.glossaryId)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `an id DeepL never knew is replaced`() {
        val transport = FakeDeepl()
        translate(transport)
        transport.glossaries.clear()
        transport.calls.clear()

        assertEquals(listOf(Translation.Done("Votre panier")), translate(transport))

        assertEquals(listOf("g1", "g2"), transport.translations().map { it.json!!["glossary_id"].asString })
        assertEquals(listOf("GET", "POST"), transport.glossaryCalls().map { it.method }, "a 404 needs no check")
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a server error with a glossary still there is reported, not taken for a deleted glossary`() {
        val transport = FakeDeepl(translateStatus = { 503 })

        val result = translate(transport)

        assertTrue(result.single() is Translation.Failed)
        assertEquals(1, transport.translations().size, "no retry")
        assertEquals("GET https://api-free.deepl.com/v3/glossaries/g1", transport.glossaryCalls().last().let { "${it.method} ${it.url}" })
        assertEquals("g1", store.glossaryId)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a glossary lost along with its id is found again by name`() {
        val transport = FakeDeepl()
        translate(transport)
        store.glossaryId = ""
        store.glossaryDictionaries = linkedMapOf()
        transport.calls.clear()

        translate(transport)

        assertEquals(listOf("GET", "PUT"), transport.glossaryCalls().map { it.method }, "no second glossary on the account")
        assertEquals("g1", transport.translations().single().json!!["glossary_id"].asString)
    }

    @Test
    fun `a quota refusal translates without the glossary and warns once`() {
        val transport = FakeDeepl(createStatus = 456)

        val result = translate(transport, texts = listOf("Your cart", "Cart"))

        assertEquals(2, result.count { it is Translation.Done })
        assertTrue(transport.translations().none { "glossary_id" in it.body!! })
        assertEquals(listOf(CoreMessages.message("translate.glossary.quota", "DeepL")), warnings)
        assertEquals("", store.glossaryId)
    }

    @Test
    fun `a glossary that cannot be recreated leaves the text translated without it`() {
        val transport = FakeDeepl()
        translate(transport)
        transport.deleteAll()
        transport.createStatus = 456

        assertEquals(listOf(Translation.Done("Votre panier")), translate(transport))

        assertFalse("glossary_id" in transport.translations().last().body!!)
        assertEquals(listOf(CoreMessages.message("translate.glossary.quota", "DeepL")), warnings)
    }

    @Test
    fun `without translated terms no glossary call is made`() {
        val transport = FakeDeepl()

        translate(transport, engine = deepl(Glossary(listOf(Glossary.Term("Acme")))))
        translate(transport, engine = deepl(Glossary.EMPTY))

        assertTrue(transport.glossaryCalls().isEmpty())
        assertTrue(transport.translations().none { "glossary_id" in it.body!! })
    }

    @Test
    fun `the other presets send the same request as before`() {
        for (id in listOf("openai-compatible", "libretranslate", "google-cloud")) {
            val preset = TranslationEngines.preset(id)!!
            assertNull(preset.nativeGlossary, id)
            val plain = mutableListOf<String>()
            val withStore = mutableListOf<String>()
            fun recorder(into: MutableList<String>) = HttpTransport { url, _, body -> into += "$url $body"; HttpTransport.Response(500, "") }
            val request = TranslationRequest(listOf("Your cart"), "en", "es")
            HttpTranslationProvider(preset, "k", recorder(plain)).translate(request)
            HttpTranslationProvider(preset.copy(glossary = glossary, remoteGlossary = store), "k", recorder(withStore)).translate(request)
            assertEquals(plain, withStore, id)
        }
        assertEquals("", store.glossaryId)
    }

    @Test
    fun `a URL that is not DeepL's translate endpoint gets no glossary`() {
        val transport = FakeDeepl()
        val gateway = deepl().copy(url = "https://gateway.example.com/deepl")

        HttpTranslationProvider(gateway, "k", transport).apply { glossaryWarnings = { warnings += it } }.translate(TranslationRequest(listOf("Your cart"), "EN", "FR"))

        assertTrue(transport.calls.none { "glossar" in it.url })
        assertFalse("glossary_id" in transport.calls.single().body!!)
    }
}
