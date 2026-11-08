package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.utils.CoreMessages
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.IOException
import java.security.MessageDigest

/**
 * The project glossary's translated terms as a DeepL glossary, so DeepL uses them instead of ignoring
 * them. Kept terms need none: they are masked before the text leaves, for every engine.
 *
 * Created the first time a language pair holding a translated term is translated, not on *Apply*:
 * translating already runs off the EDT with the API key at hand, while *Apply* runs on the EDT, and a
 * network error there would block saving the settings. One glossary per engine entry, named after its
 * id and found again by that name when its id is lost; one dictionary per pair, replaced (`PUT`) only
 * when its entries changed.
 *
 * Nothing here costs a translation: a refusal — quota (456), a pair DeepL has no glossary for, a
 * network error — is reported once through [warn] (the plugin: a balloon and the log), and the texts
 * go without a glossary.
 *
 * API: https://developers.deepl.com/api-reference/multilingual-glossaries (v3; `glossary_id` in
 * `/v2/translate` needs `source_lang`; glossary languages have no variant: `EN` serves `EN-US`).
 * Checked against the Free API: `POST` answers 201 with `glossary_id`; `PUT …/dictionaries` answers
 * 200, adds a pair as well as replacing one, and reaches translations only a few minutes later; a
 * Free account refused a second glossary with 456; `/v2/translate` answers 404 for an unknown id,
 * but 500 for a few minutes after the glossary is deleted, while its `GET /v3/glossaries/{id}` is
 * already a 404.
 */
class DeeplGlossary private constructor(
    private val engine: EngineConfig,
    private val endpoint: String,
    private val store: RemoteGlossaryStore,
    private val transport: HttpTransport,
    private val headers: () -> Map<String, String>,
    private val warn: (String) -> Unit
) {

    private class Refused(val status: Int, val detail: String) : Exception(detail)

    /** The pairs that failed during this provider's life: not asked again. */
    private val refused = mutableSetOf<String>()

    private val name: String get() = "$NAME_PREFIX${engine.id}"

    /**
     * The id of the glossary to send from [source] to [target] (DeepL codes: `EN`, `EN-US`), created or
     * updated as needed — null when no term has a translation there, or when DeepL refused it.
     */
    fun idFor(source: String, target: String): String? {
        if (source.isBlank() || target.isBlank()) return null
        val entries = entries(target).takeIf { it.isNotEmpty() } ?: return null
        val from = language(source)
        val to = language(target)
        if (from == to) return null
        val pair = "$from>$to"
        synchronized(store) {
            if (pair in refused) return null
            return try {
                ensure(pair, from, to, entries)
            } catch (e: Refused) {
                refused += pair
                warn(when (e.status) {
                    456 -> CoreMessages.message("translate.glossary.quota", engine.name)
                    else -> CoreMessages.message("translate.glossary.refused", engine.name, e.status, e.detail.take(200))
                })
                null
            } catch (e: IOException) {
                refused += pair
                warn(CoreMessages.message("translate.glossary.refused", engine.name, 0, e.message.orEmpty()))
                null
            }
        }
    }

    /** [id] is unknown to DeepL: deleted there, or another account's. The next [idFor] makes a new one. */
    fun forget(id: String) = synchronized(store) {
        if (store.glossaryId == id) {
            store.glossaryId = ""
            store.glossaryDictionaries = linkedMapOf()
        }
    }

    /**
     * Whether DeepL still has glossary [id]: false only on its 404. Any other answer, a network error
     * included, says nothing about the glossary, so it is taken as still there.
     */
    fun exists(id: String): Boolean = try {
        call("GET", "$endpoint/${encode(id)}", null).status != 404
    } catch (e: IOException) {
        true
    }

    fun warnLost() = warn(CoreMessages.message("translate.glossary.lost", engine.name))

    private fun ensure(pair: String, from: String, to: String, entries: String): String {
        val digest = digest(entries)
        val dictionary = dictionary(from, to, entries)
        if (store.glossaryId.isEmpty()) {
            val found = findByName()
            if (found == null) return create(pair, digest, dictionary)
            store.glossaryId = found
            store.glossaryDictionaries = linkedMapOf()
        }
        val id = store.glossaryId
        if (store.glossaryDictionaries[pair] == digest) return id
        val response = call("PUT", "$endpoint/${encode(id)}/dictionaries", dictionary.toString())
        if (response.status == 404) {
            forget(id)
            return create(pair, digest, dictionary)
        }
        expectSuccess(response)
        store.glossaryDictionaries[pair] = digest
        return id
    }

    private fun create(pair: String, digest: String, dictionary: JsonObject): String {
        val body = JsonObject().apply {
            addProperty("name", name)
            add("dictionaries", JsonArray().apply { add(dictionary) })
        }
        val response = expectSuccess(call("POST", endpoint, body.toString()))
        val id = HttpTranslationProvider.read(response.body, "glossary_id")
            ?: throw Refused(response.status, response.body)
        store.glossaryId = id
        store.glossaryDictionaries = linkedMapOf(pair to digest)
        return id
    }

    /** The glossary this entry made before its id was lost — settings applied from an older copy. */
    private fun findByName(): String? {
        val response = expectSuccess(call("GET", endpoint, null))
        val list = HttpTranslationProvider.node(response.body, "glossaries")?.takeIf { it.isJsonArray } ?: return null
        return list.asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .firstOrNull { it["name"]?.takeIf { n -> n.isJsonPrimitive }?.asString == name }
            ?.get("glossary_id")?.takeIf { it.isJsonPrimitive }?.asString
    }

    private fun call(method: String, url: String, body: String?): HttpTransport.Response =
        transport.request(method, url, headers(), body)

    private fun expectSuccess(response: HttpTransport.Response): HttpTransport.Response =
        response.takeIf { it.status in 200..299 } ?: throw Refused(response.status, response.body)

    /** The terms translated into [target], one `term<TAB>translation` per line; DeepL refuses tabs and line breaks inside. */
    private fun entries(target: String): String =
        engine.glossary.terms.asSequence()
            .filter { !it.kept && it.term.isNotBlank() }
            .mapNotNull { term -> term.translationFor(target)?.let { term.term.trim() to it.trim() } }
            .filter { (term, translation) -> translation.isNotEmpty() && listOf(term, translation).none { UNFIT.containsMatchIn(it) } }
            .distinctBy { it.first }
            .joinToString("\n") { (term, translation) -> "$term\t$translation" }

    private fun dictionary(from: String, to: String, entries: String) = JsonObject().apply {
        addProperty("source_lang", from)
        addProperty("target_lang", to)
        addProperty("entries", entries)
        addProperty("entries_format", "tsv")
    }

    companion object {
        const val KIND = "deepl"

        private const val NAME_PREFIX = "i18n Support Plus "
        private val TRANSLATE_PATH = Regex("""/v2/translate/?$""")
        private val UNFIT = Regex("""[\t\n\r]""")

        /** [engine]'s glossary, or null when its URL is not DeepL's `…/v2/translate` — a gateway in front. */
        fun of(
            engine: EngineConfig,
            store: RemoteGlossaryStore,
            transport: HttpTransport,
            headers: () -> Map<String, String>,
            warn: (String) -> Unit
        ): DeeplGlossary? {
            val url = engine.url.substringBefore('?')
            if (!TRANSLATE_PATH.containsMatchIn(url)) return null
            return DeeplGlossary(engine, TRANSLATE_PATH.replace(url, "/v3/glossaries"), store, transport, headers, warn)
        }

        /** [body], a JSON object, with `"glossary_id"` added last. */
        fun withId(body: String, id: String): String {
            val trimmed = body.trimEnd()
            if (!trimmed.endsWith("}")) return body
            return trimmed.dropLast(1).trimEnd() + ", \"glossary_id\": \"" + HttpTranslationProvider.escapeJson(id) + "\"}"
        }

        /** DeepL glossaries pair languages without variant, in lower case: `EN-US` → `en`. */
        private fun language(code: String): String = code.trim().substringBefore('-').substringBefore('_').lowercase()

        private fun digest(entries: String): String =
            MessageDigest.getInstance("SHA-256").digest(entries.toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it) }

        private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)
    }
}
