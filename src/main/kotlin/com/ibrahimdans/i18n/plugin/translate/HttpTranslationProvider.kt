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
    val local: Boolean = false
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

    override fun translate(request: TranslationRequest, indicator: ProgressIndicator?): List<Translation> =
        request.texts.map { text ->
            indicator?.checkCanceled()
            translateOne(text, request)
        }

    private fun translateOne(text: String, request: TranslationRequest): Translation {
        if (text.isBlank()) return Translation.Done(text)
        val masked = PlaceholderMask.mask(text)
        val values = mapOf(
            "text" to masked.text,
            "source" to request.source,
            "target" to request.target,
            "context" to request.context,
            "apiKey" to apiKey
        )
        val response = try {
            transport.post(fill(engine.url, values, ::encodeUrl), engine.headers.mapValues { fill(it.value, values) { v -> v } }, fill(engine.body, values, ::escapeJson))
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: SocketTimeoutException) {
            return failed("translate.error.timeout", engine.name)
        } catch (e: IOException) {
            return failed("translate.error.network", engine.name, e.message.orEmpty())
        }
        when (response.status) {
            in 200..299 -> Unit
            401, 403 -> return failed("translate.error.key", engine.name)
            429 -> return failed("translate.error.rate", engine.name)
            else -> return failed("translate.error.status", engine.name, response.status, response.body.take(200))
        }
        val raw = read(response.body, engine.responsePath)?.trim() ?: return failed("translate.error.response", engine.name, engine.responsePath)
        val translated = if (engine.unescapeHtml) unescapeHtml(raw) else raw
        return when (val restored = PlaceholderMask.unmask(translated, masked)) {
            is PlaceholderMask.Unmasked.Restored -> Translation.Done(restored.text)
            is PlaceholderMask.Unmasked.Rejected -> failed("translate.error.placeholders", engine.name)
        }
    }

    private fun failed(key: String, vararg params: Any) = Translation.Failed(PluginBundle.message(key, *params))

    companion object {
        private val PLACEHOLDER = Regex("""\{(text|source|target|context|apiKey)}""")
        private val PATH_PART = Regex("""([^.\[\]]+)|\[(\d+)]""")

        internal fun fill(template: String, values: Map<String, String>, encode: (String) -> String): String =
            PLACEHOLDER.replace(template) { encode(values.getValue(it.groupValues[1])) }

        /** The value at [path] (`translations[0].text`) in the JSON [body], or null when it is not a string there. */
        internal fun read(body: String, path: String): String? {
            var node: JsonElement? = try { JsonParser.parseString(body) } catch (e: Exception) { return null }
            for (part in PATH_PART.findAll(path)) {
                node = when {
                    part.groupValues[1].isNotEmpty() -> node?.takeIf { it.isJsonObject }?.asJsonObject?.get(part.groupValues[1])
                    else -> node?.takeIf { it.isJsonArray }?.asJsonArray?.let { array ->
                        part.groupValues[2].toInt().takeIf { it < array.size() }?.let(array::get)
                    }
                }
            }
            return node?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }

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
