package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.security.MessageDigest

/**
 * How to call one engine, as data: a POST to [url] with [headers] and the JSON [body], the
 * translation read at [responsePath] (`choices[0].message.content`).
 *
 * [url], [headers] and [body] take placeholders: `{text}`, `{source}`, `{target}`, `{context}`,
 * `{glossary}` (the [glossary] terms of the target locale, as a sentence of an LLM prompt) and `{apiKey}`. They are replaced by JSON-escaped content *without* quotes, so the template writes the
 * quotes itself and a placeholder can sit inside a sentence — an LLM prompt. [unescapeHtml] decodes
 * the entities an engine asked for HTML output returns (`&#39;`, `&amp;`).
 */
data class EngineConfig(
    val id: String,
    val name: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    val responsePath: String,
    val unescapeHtml: Boolean = false,
    /** Runs on the user's machine: nothing leaves it. */
    val local: Boolean = false,
    /** How to send several texts in one request, when the engine can; null sends them one by one. */
    val batch: BatchConfig? = null,
    /** The project's terms: those kept as they are are masked, the others go in `{glossary}`. */
    val glossary: Glossary = Glossary.EMPTY,
    /**
     * The engine's own glossary API the translated terms go to, named by its preset: `deepl`
     * ([DeeplGlossary]). Null for the others, which read them from `{glossary}` or not at all.
     */
    val nativeGlossary: String? = null,
    /** Where the id of that glossary is kept between sessions: the engine's entry in the settings. */
    val remoteGlossary: RemoteGlossaryStore? = null
)

/**
 * The project's terms, so an engine neither translates a product name nor varies a business term.
 *
 * - A term without translations is **kept as it is**, for every engine: masked like a variable before
 *   the text leaves ([protect]) and put back as written. Matched case-sensitively, as a whole word.
 * - A term with translations, by target locale, is given to the engines whose request holds
 *   `{glossary}` — an LLM prompt ([prompt]), and to DeepL as a glossary of its own ([DeeplGlossary]). Google and LibreTranslate do not read it.
 */
data class Glossary(val terms: List<Term> = emptyList()) {

    /** [term] and its [translations] by locale (`fr`, `pt_BR`); none means "never translate it". */
    data class Term(val term: String, val translations: Map<String, String> = emptyMap()) {
        val kept: Boolean get() = translations.isEmpty()

        /**
         * The translation for [target], which may come rewritten for its engine (`FR`, `EN-US`):
         * the locale written alike, else the bare language, else another variant of it.
         */
        fun translationFor(target: String): String? {
            val wanted = normalize(target)
            val language = wanted.substringBefore('-')
            val byLocale = translations.entries.filter { it.value.isNotBlank() }.associate { normalize(it.key) to it.value }
            return byLocale[wanted] ?: byLocale[language] ?: byLocale.entries.firstOrNull { it.key.substringBefore('-') == language }?.value
        }
    }

    /** One pattern for every kept term, the longest first, or null when none is kept. */
    private val keptPattern: Regex? by lazy {
        terms.filter { it.kept && it.term.isNotBlank() }.map { it.term }.distinct().sortedByDescending { it.length }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("|") { term ->
                val before = if (term.first().isLetterOrDigit()) "(?<![\\p{L}\\p{N}_])" else ""
                val after = if (term.last().isLetterOrDigit()) "(?![\\p{L}\\p{N}_])" else ""
                before + Regex.escape(term) + after
            }
            ?.let(::Regex)
    }

    /**
     * [masked] with each kept term hidden behind one more `<x id="N"/>` tag, a variable the
     * translation may move. The tags already there are left alone.
     */
    fun protect(masked: PlaceholderMask.Masked): PlaceholderMask.Masked {
        val pattern = keptPattern ?: return masked
        val tokens = masked.tokens.toMutableList()
        val out = StringBuilder()
        fun gap(text: String) {
            out.append(pattern.replace(text) { match ->
                "<x id=\"${tokens.size}\"/>".also { tokens += PlaceholderMask.Token(PlaceholderMask.Kind.VARIABLE, match.value) }
            })
        }
        var last = 0
        for (tag in MASK_TAG.findAll(masked.text)) {
            gap(masked.text.substring(last, tag.range.first))
            out.append(tag.value)
            last = tag.range.last + 1
        }
        gap(masked.text.substring(last))
        return if (tokens.size == masked.tokens.size) masked else PlaceholderMask.Masked(out.toString(), tokens)
    }

    /**
     * What `{glossary}` becomes for [target]: a sentence opening with a space, to follow the
     * prompt's last one — empty when no term has a translation there, leaving the request as it was.
     */
    fun prompt(target: String): String {
        val pairs = terms.filter { !it.kept && it.term.isNotBlank() }.mapNotNull { t -> t.translationFor(target)?.let { t.term to it } }
        if (pairs.isEmpty()) return ""
        return " Use this glossary, each term with its translation: " +
            pairs.joinToString("; ") { (term, translation) -> "\"$term\" → \"$translation\"" } + "."
    }

    companion object {
        val EMPTY = Glossary()

        private val MASK_TAG = Regex("""<x id="\d+"/>""")

        private fun normalize(locale: String): String = locale.trim().lowercase().replace('_', '-')
    }
}

/**
 * The batch form of an engine: [body] takes `{texts}` — the JSON array of the texts — or
 * `{textsJson}`, the same array escaped for inside a JSON string (an LLM prompt), besides the other
 * placeholders. The translations are the array at [listPath], each one the string at [itemField] of
 * its element (the element itself when blank). [jsonText] says the value at [listPath] is a string
 * holding that array — an LLM's answer. At most [maxSize] texts go in one request.
 */
data class BatchConfig(
    val body: String,
    val listPath: String,
    val itemField: String = "",
    val jsonText: Boolean = false,
    val maxSize: Int
)

/** One HTTP exchange, so tests never reach the network. */
fun interface HttpTransport {
    data class Response(val status: Int, val body: String)

    fun post(url: String, headers: Map<String, String>, body: String): Response

    /**
     * Another verb — `GET`, `PUT` — for what an engine needs besides translating: a DeepL glossary.
     * A transport that only knows [post] refuses the others as a network error.
     */
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): Response =
        if (method == "POST") post(url, headers, body.orEmpty()) else throw IOException("HTTP $method is not supported here")
}

/**
 * Any engine reachable by one JSON POST per text — DeepL, LibreTranslate, Google Cloud, every
 * OpenAI-compatible endpoint (Ollama, OpenAI, Mistral…) — configured by an [EngineConfig].
 *
 * Each text goes through [PlaceholderMask] first: the engine only ever sees `<x id="N"/>` where a
 * variable, a tag or ICU syntax stood, and a translation losing one is refused. Nothing throws but
 * a cancellation: a network error, a refused key or an unreadable answer is a [Translation.Failed].
 */
class HttpTranslationProvider(
    private val engine: EngineConfig,
    private val apiKey: String,
    private val transport: HttpTransport = PlatformHttpTransport
) : TranslationProvider {

    /**
     * Says the engine's glossary could not be used: the translation went on without it. A balloon by
     * default; not a constructor parameter, which would take the transport's trailing lambda.
     */
    internal var glossaryWarnings: (String) -> Unit = { DeeplGlossary.warnUser(it) }

    private val deepl: DeeplGlossary? = engine.takeIf { it.nativeGlossary == DeeplGlossary.KIND }
        ?.let { DeeplGlossary.of(it, it.remoteGlossary ?: InMemoryGlossaryStore(), transport, ::headers) { message -> glossaryWarnings(message) } }

    override fun translate(request: TranslationRequest, indicator: ProgressIndicator?): List<Translation> {
        val glossary = deepl?.let { PairGlossary(it, request.source, request.target) }
        val batch = engine.batch
        if (batch == null || request.texts.size < 2) {
            return request.texts.map { text ->
                indicator?.checkCanceled()
                translateOne(text, request, glossary)
            }
        }
        return request.texts.chunked(batch.maxSize.coerceAtLeast(1)).flatMap { chunk ->
            indicator?.checkCanceled()
            translateBatch(chunk, request, batch, glossary)
        }
    }

    /**
     * The engine's glossary for one request's language pair, looked up when the first text goes out —
     * never when every text is blank. A glossary the engine no longer knows is recreated once.
     */
    private class PairGlossary(private val deepl: DeeplGlossary, private val source: String, private val target: String) {
        private var resolved = false
        private var recreated = false
        private var id: String? = null

        fun id(): String? {
            if (!resolved) {
                resolved = true
                id = deepl.idFor(source, target)
            }
            return id
        }

        fun exists(id: String): Boolean = deepl.exists(id)

        /** [gone] is unknown to DeepL: the id to try instead, or null to send without a glossary. */
        fun replace(gone: String): String? {
            deepl.forget(gone)
            id = if (recreated) null.also { deepl.warnLost() } else deepl.idFor(source, target)
            recreated = true
            return id
        }
    }

    /**
     * [body] posted with the glossary of [glossary] when there is one, else as it is. A glossary
     * deleted on the engine's side is recreated, and the text goes without one if that fails too — a
     * glossary never costs a translation. DeepL answers 404 for an unknown id, but 500 for a few minutes
     * after a glossary is deleted: a 5xx is checked against the glossary itself before being taken as an outage.
     */
    private fun send(url: String, body: String, glossary: PairGlossary?): Posted {
        var id = glossary?.id() ?: return post(url, body)
        while (true) {
            val posted = post(url, DeeplGlossary.withId(body, id))
            val status = (posted as? Posted.Failed)?.status ?: return posted
            val gone = status == 404 || (status >= 500 && !glossary.exists(id))
            if (!gone) return posted
            id = glossary.replace(id) ?: return post(url, body)
        }
    }

    private fun headers(): Map<String, String> = engine.headers.mapValues { fill(it.value, mapOf("apiKey" to apiKey)) { v -> v } }

    /**
     * [texts] in one request. An answer that cannot be read, or that does not hold exactly one
     * translation per text, is retried text by text: a shifted list would put each translation under
     * the wrong key. An HTTP or network error is the same for every text, so it is not retried.
     */
    private fun translateBatch(texts: List<String>, request: TranslationRequest, batch: BatchConfig, glossary: PairGlossary?): List<Translation> {
        val sent = texts.withIndex().filter { it.value.isNotBlank() }
        if (sent.size < 2) return texts.map { translateOne(it, request, glossary) }
        val masks = sent.map { engine.glossary.protect(PlaceholderMask.mask(it.value)) }
        val array = masks.joinToString(",", "[", "]") { "\"${escapeJson(it.text)}\"" }
        val body = fill(batch.body.replace("{texts}", array).replace("{textsJson}", escapeJson(array)), values(request, ""), ::escapeJson)
        val answers = when (val response = send(fill(engine.url, values(request, ""), ::encodeUrl), body, glossary)) {
            is Posted.Failed -> return texts.map { if (it.isBlank()) Translation.Done(it) else response.failure }
            is Posted.Answered -> readList(response.body, batch)
        }
        if (answers == null || answers.size != sent.size) return texts.map { translateOne(it, request, glossary) }
        val results = MutableList<Translation>(texts.size) { Translation.Done(texts[it]) }
        sent.forEachIndexed { i, (index, _) -> results[index] = restore(answers[i], masks[i]) }
        return results
    }

    private fun values(request: TranslationRequest, text: String) = mapOf(
        "text" to text,
        "source" to request.source,
        "target" to request.target,
        "context" to request.context,
        "glossary" to engine.glossary.prompt(request.target),
        "apiKey" to apiKey
    )

    /** What one POST gave: an answer to read, or the failure every text it carried gets. */
    private sealed interface Posted {
        data class Answered(val body: String) : Posted
        /** [status] is the HTTP one, 0 for a network error. */
        data class Failed(val failure: Translation.Failed, val status: Int = 0) : Posted
    }

    private fun post(url: String, body: String): Posted {
        val response = try {
            transport.post(url, headers(), body)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: SocketTimeoutException) {
            return Posted.Failed(failed("translate.error.timeout", engine.name))
        } catch (e: IOException) {
            return Posted.Failed(failed("translate.error.network", engine.name, e.message.orEmpty()))
        }
        return when (response.status) {
            in 200..299 -> Posted.Answered(response.body)
            401, 403 -> Posted.Failed(failed("translate.error.key", engine.name), response.status)
            429 -> Posted.Failed(failed("translate.error.rate", engine.name), response.status)
            else -> Posted.Failed(failed("translate.error.status", engine.name, response.status, response.body.take(200)), response.status)
        }
    }

    /** The translations of a batch answer, or null when [body] does not hold a list of strings where [batch] says. */
    private fun readList(body: String, batch: BatchConfig): List<String>? {
        var list = node(body, batch.listPath) ?: return null
        if (batch.jsonText) {
            val text = list.takeIf { it.isJsonPrimitive }?.asString ?: return null
            list = try { JsonParser.parseString(stripFences(text)) } catch (e: Exception) { return null }
        }
        if (!list.isJsonArray) return null
        return list.asJsonArray.map { element ->
            val item = if (batch.itemField.isBlank()) element else element.takeIf { it.isJsonObject }?.asJsonObject?.get(batch.itemField)
            item?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
        }
    }

    private fun restore(raw: String, masked: PlaceholderMask.Masked): Translation {
        val translated = if (engine.unescapeHtml) unescapeHtml(raw.trim()) else raw.trim()
        return when (val restored = PlaceholderMask.unmask(translated, masked)) {
            is PlaceholderMask.Unmasked.Restored -> Translation.Done(restored.text, TranslatedBy(engine.name, engine.local))
            is PlaceholderMask.Unmasked.Rejected -> failed("translate.error.placeholders", engine.name)
        }
    }

    private fun translateOne(text: String, request: TranslationRequest, glossary: PairGlossary?): Translation {
        if (text.isBlank()) return Translation.Done(text)
        val masked = engine.glossary.protect(PlaceholderMask.mask(text))
        val values = values(request, masked.text)
        val body = when (val response = send(fill(engine.url, values, ::encodeUrl), fill(engine.body, values, ::escapeJson), glossary)) {
            is Posted.Failed -> return response.failure
            is Posted.Answered -> response.body
        }
        val raw = read(body, engine.responsePath) ?: return failed("translate.error.response", engine.name, engine.responsePath)
        return restore(raw, masked)
    }

    private fun failed(key: String, vararg params: Any) = Translation.Failed(PluginBundle.message(key, *params))

    companion object {
        private val PLACEHOLDER = Regex("""\{(text|source|target|context|glossary|apiKey)}""")
        private val FENCE = Regex("""^\s*```\w*\s*|\s*```\s*$""")
        private val PATH_PART = Regex("""([^.\[\]]+)|\[(\d+)]""")

        internal fun fill(template: String, values: Map<String, String>, encode: (String) -> String): String =
            PLACEHOLDER.replace(template) { match -> values[match.groupValues[1]]?.let(encode) ?: match.value }

        /** The value at [path] (`translations[0].text`) in the JSON [body], or null when it is not a string there. */
        internal fun read(body: String, path: String): String? =
            node(body, path)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        /** The JSON node at [path] in [body], or null when the path leads nowhere. */
        internal fun node(body: String, path: String): JsonElement? {
            var node: JsonElement? = try { JsonParser.parseString(body) } catch (e: Exception) { return null }
            for (part in PATH_PART.findAll(path)) {
                node = when {
                    part.groupValues[1].isNotEmpty() -> node?.takeIf { it.isJsonObject }?.asJsonObject?.get(part.groupValues[1])
                    else -> node?.takeIf { it.isJsonArray }?.asJsonArray?.let { array ->
                        part.groupValues[2].toInt().takeIf { it < array.size() }?.let(array::get)
                    }
                }
            }
            return node
        }

        /** An LLM's answer without the Markdown code fence it may wrap JSON in. */
        internal fun stripFences(text: String): String = FENCE.replace(text.trim(), "")

        /** Content for inside a JSON string: the template writes the quotes. */
        internal fun escapeJson(value: String): String = buildString {
            for (c in value) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }

        private fun encodeUrl(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

        private val ENTITY = Regex("""&(#\d+|#x[0-9a-fA-F]+|amp|lt|gt|quot|apos);""")

        internal fun unescapeHtml(text: String): String = ENTITY.replace(text) {
            when (val name = it.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> String(Character.toChars(if (name.startsWith("#x")) name.drop(2).toInt(16) else name.drop(1).toInt()))
            }
        }
    }
}

/**
 * Where an engine keeps its glossary on the engine's side: its [glossaryId], empty before the first
 * one, and what each language pair holds there — `en>fr` to a digest of the entries sent.
 */
interface RemoteGlossaryStore {
    var glossaryId: String
    var glossaryDictionaries: MutableMap<String, String>
}

/** A glossary remembered for the life of one provider only — the *Test* of an engine, a test. */
class InMemoryGlossaryStore(
    override var glossaryId: String = "",
    override var glossaryDictionaries: MutableMap<String, String> = linkedMapOf()
) : RemoteGlossaryStore

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
 * network error — is reported once through [warn], and the texts go without a glossary.
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
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Refused) {
                refused += pair
                warn(when (e.status) {
                    456 -> PluginBundle.message("translate.glossary.quota", engine.name)
                    else -> PluginBundle.message("translate.glossary.refused", engine.name, e.status, e.detail.take(200))
                })
                null
            } catch (e: IOException) {
                refused += pair
                warn(PluginBundle.message("translate.glossary.refused", engine.name, 0, e.message.orEmpty()))
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
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: IOException) {
        true
    }

    fun warnLost() = warn(PluginBundle.message("translate.glossary.lost", engine.name))

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
        private val LOG = Logger.getInstance(DeeplGlossary::class.java)

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

        /** A balloon of the plugin's group, and the log. */
        fun warnUser(message: String) {
            LOG.warn(message)
            if (ApplicationManager.getApplication() == null) return
            NotificationGroupManager.getInstance().getNotificationGroup("i18n Support Plus")
                ?.createNotification(message, NotificationType.WARNING)
                ?.notify(null)
        }

        /** DeepL glossaries pair languages without variant, in lower case: `EN-US` → `en`. */
        private fun language(code: String): String = code.trim().substringBefore('-').substringBefore('_').lowercase()

        private fun digest(entries: String): String =
            MessageDigest.getInstance("SHA-256").digest(entries.toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it) }

        private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)
    }
}

/** The platform's HTTP client: the IDE's proxy settings apply. */
object PlatformHttpTransport : HttpTransport {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val JSON = "application/json"

    override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response =
        request("POST", url, headers, body)

    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpTransport.Response =
        when (method) {
            "POST" -> HttpRequests.post(url, JSON)
            "PUT" -> HttpRequests.put(url, JSON)
            "GET" -> HttpRequests.request(url)
            else -> throw IOException("HTTP $method is not supported here")
        }
            .tuner { connection -> headers.forEach { (name, value) -> connection.setRequestProperty(name, value) } }
            .connectTimeout(CONNECT_TIMEOUT_MS)
            .readTimeout(READ_TIMEOUT_MS)
            .throwStatusCodeException(false)
            .connect { request ->
                if (body != null) request.write(body)
                val connection = request.connection as HttpURLConnection
                val status = connection.responseCode
                val text = if (status in 200..299) request.readString()
                else connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                HttpTransport.Response(status, text)
            }
}
