package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.utils.CoreMessages
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration

/**
 * Any engine reachable by one JSON POST per text — DeepL, LibreTranslate, Google Cloud, every
 * OpenAI-compatible endpoint (Ollama, OpenAI, Mistral…) — configured by an [EngineConfig].
 *
 * Each text goes through [PlaceholderMask] first: the engine only ever sees `<x id="N"/>` where a
 * variable, a tag or ICU syntax stood, and a translation losing one is refused. Nothing throws but
 * a cancellation: a network error, a refused key or an unreadable answer is a [Translation.Failed].
 *
 * [transport] has no default: inside the IDE it is the plugin's, which follows the IDE's proxy.
 */
class HttpTranslationProvider(
    private val engine: EngineConfig,
    private val apiKey: String,
    private val transport: HttpTransport
) : TranslationProvider {

    /**
     * Says the engine's glossary could not be used: the translation went on without it. Silent by
     * default — the plugin shows a balloon; not a constructor parameter, which would take the
     * transport's trailing lambda.
     */
    var glossaryWarnings: (String) -> Unit = {}

    /** How the provider waits before trying a request again; replaced in tests, for the same reason. */
    var pause: Pause = Pause.SLEEP

    private val deepl: DeeplGlossary? = engine.takeIf { it.nativeGlossary == DeeplGlossary.KIND }
        ?.let { DeeplGlossary.of(it, it.remoteGlossary ?: InMemoryGlossaryStore(), transport, ::headers) { message -> glossaryWarnings(message) } }

    override fun translate(request: TranslationRequest, cancellation: Cancellation?): List<Translation> {
        val glossary = deepl?.let { PairGlossary(it, request.source, request.target) }
        val batch = engine.batch
        if (batch == null || request.texts.size < 2) {
            return request.texts.map { text ->
                cancellation?.check()
                translateOne(text, request, glossary, cancellation)
            }
        }
        return request.texts.chunked(batch.maxSize.coerceAtLeast(1)).flatMap { chunk ->
            cancellation?.check()
            translateBatch(chunk, request, batch, glossary, cancellation)
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
    private fun send(url: String, body: String, glossary: PairGlossary?, texts: Int, cancellation: Cancellation?): Posted {
        val timeout = engine.readTimeout ?: readTimeoutFor(texts)
        var id = glossary?.id() ?: return post(url, body, timeout, cancellation)
        while (true) {
            val posted = post(url, DeeplGlossary.withId(body, id), timeout, cancellation)
            val status = (posted as? Posted.Failed)?.status ?: return posted
            val gone = status == 404 || (status >= 500 && !glossary.exists(id))
            if (!gone) return posted
            id = glossary.replace(id) ?: return post(url, body, timeout, cancellation)
        }
    }

    private fun headers(): Map<String, String> = engine.headers.mapValues { fill(it.value, mapOf("apiKey" to apiKey)) { v -> v } }

    /**
     * [texts] in one request. An answer that cannot be read, or that does not hold exactly one
     * translation per text, is retried text by text: a shifted list would put each translation under
     * the wrong key. A request the engine could not handle — refused as malformed or too large, a server
     * error, no answer in time — is sent again in two halves, down to one text, so that a text it chokes
     * on fails alone. A refused key or a rate limit is the same for every text: the batch fails at once.
     */
    private fun translateBatch(
        texts: List<String>, request: TranslationRequest, batch: BatchConfig, glossary: PairGlossary?, cancellation: Cancellation?
    ): List<Translation> {
        val sent = texts.withIndex().filter { it.value.isNotBlank() }
        if (sent.size < 2) return texts.map { translateOne(it, request, glossary, cancellation) }
        val masks = sent.map { engine.glossary.protect(PlaceholderMask.mask(it.value)) }
        val array = masks.joinToString(",", "[", "]") { "\"${escapeJson(it.text)}\"" }
        val body = fill(batch.body.replace("{texts}", array).replace("{textsJson}", escapeJson(array)), values(request, ""), ::escapeJson)
        val answers = when (val response = send(fill(engine.url, values(request, ""), ::encodeUrl), body, glossary, sent.size, cancellation)) {
            is Posted.Failed -> {
                if (!response.splittable) return texts.map { if (it.isBlank()) Translation.Done(it) else response.failure }
                val half = texts.size / 2
                return translateBatch(texts.take(half), request, batch, glossary, cancellation) +
                    translateBatch(texts.drop(half), request, batch, glossary, cancellation)
            }
            is Posted.Answered -> readList(response.body, batch)
        }
        if (answers == null || answers.size != sent.size) return texts.map { translateOne(it, request, glossary, cancellation) }
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
        "formality" to engine.style.formalityFor(request.target).deepl,
        "instruction" to engine.style.formalityFor(request.target).sentence +
            instructionPrompt(*engine.style.instruction.lines().toTypedArray(), request.instruction),
        "apiKey" to apiKey
    )

    /** What one POST gave: an answer to read, or the failure every text it carried gets. */
    private sealed interface Posted {
        data class Answered(val body: String) : Posted

        /**
         * [status] is the HTTP one, 0 for a network error; [timedOut] says no answer came in time, and
         * [retryAfterMillis] is how long the engine asked to wait before the next attempt.
         */
        data class Failed(
            val failure: Translation.Failed,
            val status: Int = 0,
            val timedOut: Boolean = false,
            val retryAfterMillis: Long? = null
        ) : Posted {
            /** A rate limit, a gateway or server momentarily down, or no answer in time: worth another try. */
            val retryable: Boolean get() = timedOut || status in RETRYABLE

            /** A failure a smaller request may avoid: refused as malformed or too large, a server error, a timeout. */
            val splittable: Boolean get() = timedOut || status in SPLITTABLE || status >= 500
        }
    }

    /**
     * [body] posted to [url], tried [ATTEMPTS] times in all when the engine is rate-limited, momentarily
     * down or late, after [RETRY_DELAYS_MS] — or the delay its `Retry-After` asks for, capped at
     * [MAX_RETRY_AFTER_S] seconds. Every other failure is final at once: another try would get it again.
     */
    private fun post(url: String, body: String, readTimeout: Duration, cancellation: Cancellation?): Posted {
        var attempt = 1
        while (true) {
            val posted = postOnce(url, body, readTimeout)
            if (posted !is Posted.Failed || !posted.retryable || attempt >= ATTEMPTS) return posted
            pause.pause(posted.retryAfterMillis ?: RETRY_DELAYS_MS[attempt - 1], cancellation)
            attempt++
        }
    }

    private fun postOnce(url: String, body: String, readTimeout: Duration): Posted {
        val response = try {
            transport.post(url, headers(), body, readTimeout)
        } catch (e: SocketTimeoutException) {
            return Posted.Failed(failed(CoreMessages.message("translate.error.timeout", engine.name)), timedOut = true)
        } catch (e: IOException) {
            return Posted.Failed(failed(CoreMessages.message("translate.error.network", engine.name, e.message.orEmpty())))
        }
        val retryAfter = retryAfterMillis(response.retryAfter)
        return when (response.status) {
            in 200..299 -> Posted.Answered(response.body)
            401, 403 -> Posted.Failed(failed(CoreMessages.message("translate.error.key", engine.name)), response.status)
            429 -> Posted.Failed(failed(CoreMessages.message("translate.error.rate", engine.name)), response.status, retryAfterMillis = retryAfter)
            else -> Posted.Failed(
                failed(CoreMessages.message("translate.error.status", engine.name, response.status, response.body.take(200))),
                response.status,
                retryAfterMillis = retryAfter
            )
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
            is PlaceholderMask.Unmasked.Rejected -> failed(CoreMessages.message("translate.error.placeholders", engine.name))
        }
    }

    private fun translateOne(text: String, request: TranslationRequest, glossary: PairGlossary?, cancellation: Cancellation?): Translation {
        if (text.isBlank()) return Translation.Done(text)
        val masked = engine.glossary.protect(PlaceholderMask.mask(text))
        val values = values(request, masked.text)
        val body = when (val response = send(fill(engine.url, values, ::encodeUrl), fill(engine.body, values, ::escapeJson), glossary, 1, cancellation)) {
            is Posted.Failed -> return response.failure
            is Posted.Answered -> response.body
        }
        val raw = read(body, engine.responsePath) ?: return failed(CoreMessages.message("translate.error.response", engine.name, engine.responsePath))
        return restore(raw, masked)
    }

    // The key is spelled out at each call, so CoreMessagesTest finds it in the bundles.
    private fun failed(message: String) = Translation.Failed(message)

    companion object {
        private val PLACEHOLDER = Regex("""\{(text|source|target|context|glossary|instruction|formality|apiKey)}""")
        private val FENCE = Regex("""^\s*```\w*\s*|\s*```\s*$""")
        private val PATH_PART = Regex("""([^.\[\]]+)|\[(\d+)]""")

        /** Attempts at one request, the first included. */
        private const val ATTEMPTS = 3

        /** The wait before the second attempt, then before the third. */
        private val RETRY_DELAYS_MS = listOf(1_000L, 2_000L)
        private const val MAX_RETRY_AFTER_S = 30L
        private val RETRYABLE = setOf(429, 502, 503, 504)
        private val SPLITTABLE = setOf(400, 413, 422)

        private val BASE_READ_TIMEOUT: Duration = Duration.ofSeconds(60)
        private val READ_TIMEOUT_PER_TEXT: Duration = Duration.ofSeconds(2)
        private val MAX_READ_TIMEOUT: Duration = Duration.ofMinutes(5)

        /** How long to wait for an answer to [texts] texts: a minute, two more seconds a text, five minutes at most. */
        internal fun readTimeoutFor(texts: Int): Duration =
            minOf(BASE_READ_TIMEOUT.plus(READ_TIMEOUT_PER_TEXT.multipliedBy(texts.coerceAtLeast(1).toLong())), MAX_READ_TIMEOUT)

        /**
         * The wait a `Retry-After` header asks for, in milliseconds, at most [MAX_RETRY_AFTER_S] seconds; null
         * for a header absent or not a whole number of seconds — an HTTP date included —, which leaves the
         * default delay.
         */
        internal fun retryAfterMillis(header: String?): Long? =
            header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { minOf(it, MAX_RETRY_AFTER_S) * 1_000 }

        /**
         * The user's part of `{instruction}`: [instructions] — the project's lines, then the key's — as a
         * sentence opening with a space, to follow the prompt's last one. The blank ones are skipped, and
         * none leaves the request as it was.
         */
        fun instructionPrompt(vararg instructions: String): String {
            val given = instructions.map { it.trim().trimEnd('.').trim() }.filter { it.isNotEmpty() }
            return when (given.size) {
                0 -> ""
                1 -> " Also follow this instruction from the user: ${given.single()}."
                else -> " Also follow these instructions from the user: ${given.joinToString(". ")}."
            }
        }

        fun fill(template: String, values: Map<String, String>, encode: (String) -> String): String =
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
        fun escapeJson(value: String): String = buildString {
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
