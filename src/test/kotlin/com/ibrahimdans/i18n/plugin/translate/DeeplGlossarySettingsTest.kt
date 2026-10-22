package com.ibrahimdans.i18n.plugin.translate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The DeepL glossary as the settings hold it: which entries get one, and where its id is kept. The
 * glossary calls themselves are covered in `core` (DeeplGlossaryTest); a fake DeepL answers here too.
 */
class DeeplGlossarySettingsTest {

    private val glossary = Glossary(listOf(Glossary.Term("cart", mapOf("fr" to "panier"))))

    /** DeepL with no glossary yet: the list is empty, a creation answers `g1`, a translation succeeds. */
    private class FakeDeepl : HttpTransport {
        val urls = mutableListOf<String>()

        override fun post(url: String, headers: Map<String, String>, body: String) = request("POST", url, headers, body)

        override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpTransport.Response {
            urls += url
            return when {
                url.endsWith("/v3/glossaries") && method == "GET" -> HttpTransport.Response(200, """{"glossaries":[]}""")
                url.endsWith("/v3/glossaries") && method == "POST" -> HttpTransport.Response(201, """{"glossary_id":"g1"}""")
                url.endsWith("/v2/translate") -> HttpTransport.Response(200, """{"translations":[{"text":"Votre panier"}]}""")
                else -> HttpTransport.Response(500, "unexpected $method $url")
            }
        }
    }

    @Test
    fun `a Pro URL keeps the glossary, a rewritten body drops it, and the id is persisted`() {
        val entry = EngineState.of(TranslationEngines.preset("deepl")!!)
        assertEquals(DeeplGlossary.KIND, entry.copy(url = "https://api.deepl.com/v2/translate").toConfig().nativeGlossary)
        assertNull(entry.copy(body = """{"text":["{text}"]}""").toConfig().nativeGlossary)

        val transport = FakeDeepl()
        val state = entry.copy(url = "https://api.deepl.com/v2/translate")
        HttpTranslationProvider(state.toConfig(glossary), "k", transport).translate(TranslationRequest(listOf("Your cart"), "EN", "FR"))

        assertEquals("https://api.deepl.com/v3/glossaries", transport.urls.last { "/v3/glossaries" in it })
        assertEquals("g1", state.glossaryId)
        assertEquals(setOf("en>fr"), state.glossaryDictionaries.keys)
    }
}
