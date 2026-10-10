package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException

/**
 * How to call one engine, as data: a POST to [url] with [headers] and the JSON [body], the
 * translation read at [responsePath] (`choices[0].message.content`).
 *
 * [url], [headers] and [body] take placeholders: `{text}`, `{source}`, `{target}`, `{context}` and
 * `{apiKey}`. They are replaced by JSON-escaped content *without* quotes, so the template writes the
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
    val batch: BatchConfig? = null
)

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

    override fun translate(request: TranslationRequest, indicator: ProgressIndicator?): List<Translation> {
        val batch = engine.batch
        if (batch == null || request.texts.size < 2) {
            return request.texts.map { text ->
                indicator?.checkCanceled()
                translateOne(text, request)
            }
        }
        return request.texts.chunked(batch.maxSize.coerceAtLeast(1)).flatMap { chunk ->
            indicator?.checkCanceled()
            translateBatch(chunk, request, batch)
        }
    }

    /**
     * [texts] in one request. An answer that cannot be read, or that does not hold exactly one
     * translation per text, is retried text by text: a shifted list would put each translation under
     * the wrong key. An HTTP or network error is the same for every text, so it is not retried.
     */
    private fun translateBatch(texts: List<String>, request: TranslationRequest, batch: BatchConfig): List<Translation> {
        val sent = texts.withIndex().filter { it.value.isNotBlank() }
        if (sent.size < 2) return texts.map { translateOne(it, request) }
        val masks = sent.map { PlaceholderMask.mask(it.value) }
        val array = masks.joinToString(",", "[", "]") { "\"${escapeJson(it.text)}\"" }
        val body = fill(batch.body.replace("{texts}", array).replace("{textsJson}", escapeJson(array)), values(request, ""), ::escapeJson)
        val answers = when (val response = post(fill(engine.url, values(request, ""), ::encodeUrl), body)) {
            is Posted.Failed -> return texts.map { if (it.isBlank()) Translation.Done(it) else response.failure }
            is Posted.Answered -> readList(response.body, batch)
        }
        if (answers == null || answers.size != sent.size) return texts.map { translateOne(it, request) }
        val results = MutableList<Translation>(texts.size) { Translation.Done(texts[it]) }
        sent.forEachIndexed { i, (index, _) -> results[index] = restore(answers[i], masks[i]) }
        return results
    }

    private fun values(request: TranslationRequest, text: String) = mapOf(
        "text" to text,
        "source" to request.source,
        "target" to request.target,
        "context" to request.context,
        "apiKey" to apiKey
    )

    /** What one POST gave: an answer to read, or the failure every text it carried gets. */
    private sealed interface Posted {
        data class Answered(val body: String) : Posted
        data class Failed(val failure: Translation.Failed) : Posted
    }

    private fun post(url: String, body: String): Posted {
        val response = try {
            transport.post(url, engine.headers.mapValues { fill(it.value, mapOf("apiKey" to apiKey)) { v -> v } }, body)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: SocketTimeoutException) {
            return Posted.Failed(failed("translate.error.timeout", engine.name))
        } catch (e: IOException) {
            return Posted.Failed(failed("translate.error.network", engine.name, e.message.orEmpty()))
        }
        return when (response.status) {
            in 200..299 -> Posted.Answered(response.body)
            401, 403 -> Posted.Failed(failed("translate.error.key", engine.name))
            429 -> Posted.Failed(failed("translate.error.rate", engine.name))
            else -> Posted.Failed(failed("translate.error.status", engine.name, response.status, response.body.take(200)))
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
            is PlaceholderMask.Unmasked.Restored -> Translation.Done(restored.text)
            is PlaceholderMask.Unmasked.Rejected -> failed("translate.error.placeholders", engine.name)
        }
    }

    private fun translateOne(text: String, request: TranslationRequest): Translation {
        if (text.isBlank()) return Translation.Done(text)
        val masked = PlaceholderMask.mask(text)
        val values = values(request, masked.text)
        val body = when (val response = post(fill(engine.url, values, ::encodeUrl), fill(engine.body, values, ::escapeJson))) {
            is Posted.Failed -> return response.failure
            is Posted.Answered -> response.body
        }
        val raw = read(body, engine.responsePath) ?: return failed("translate.error.response", engine.name, engine.responsePath)
        return restore(raw, masked)
    }

    private fun failed(key: String, vararg params: Any) = Translation.Failed(PluginBundle.message(key, *params))

    companion object {
        private val PLACEHOLDER = Regex("""\{(text|source|target|context|apiKey)}""")
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

/** The platform's HTTP client: the IDE's proxy settings apply. */
object PlatformHttpTransport : HttpTransport {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 60_000

    override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response =
        HttpRequests.post(url, "application/json")
            .tuner { connection -> headers.forEach { (name, value) -> connection.setRequestProperty(name, value) } }
            .connectTimeout(CONNECT_TIMEOUT_MS)
            .readTimeout(READ_TIMEOUT_MS)
            .throwStatusCodeException(false)
            .connect { request ->
                request.write(body)
                val connection = request.connection as HttpURLConnection
                val status = connection.responseCode
                val text = if (status in 200..299) request.readString()
                else connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                HttpTransport.Response(status, text)
            }
}
