package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.utils.CoreMessages
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration

/**
 * A rate limit or a gateway down for a moment is tried again before a text is given up, and a batch the
 * engine chokes on or answers too late is split rather than lost. The waits are recorded, never slept.
 */
class HttpTranslationRetryTest {

    /** Answers each call in turn from [answers], the last one repeated, recording what was sent. */
    private class ScriptedTransport(vararg answers: () -> HttpTransport.Response) : HttpTransport {
        private val script = answers.toList()
        val bodies = mutableListOf<String>()
        val timeouts = mutableListOf<Duration>()

        override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response =
            post(url, headers, body, Duration.ZERO)

        override fun post(url: String, headers: Map<String, String>, body: String, readTimeout: Duration): HttpTransport.Response {
            bodies += body
            timeouts += readTimeout
            return script[minOf(bodies.size, script.size) - 1]()
        }
    }

    private val pauses = mutableListOf<Long>()

    private fun provider(engine: EngineConfig, transport: HttpTransport) =
        HttpTranslationProvider(engine, "k", transport).apply { pause = Pause { millis, _ -> pauses += millis } }

    private val deepl = TranslationEngines.preset("deepl")!!
    private val oneByOne = deepl.copy(batch = null)

    private fun status(code: Int, retryAfter: String? = null): () -> HttpTransport.Response = { HttpTransport.Response(code, "", retryAfter) }
    private val ok: () -> HttpTransport.Response = { HttpTransport.Response(200, """{"translations":[{"text":"Bonjour"}]}""") }

    private fun translate(transport: HttpTransport, engine: EngineConfig = oneByOne, vararg texts: String = arrayOf("Hello")) =
        provider(engine, transport).translate(TranslationRequest(texts.toList(), "en", "fr"))

    @Test
    fun `a rate limit is tried again after a second`() {
        val transport = ScriptedTransport(status(429), ok)

        assertEquals(listOf(Translation.Done("Bonjour", TranslatedBy("DeepL", false))), translate(transport))
        assertEquals(2, transport.bodies.size)
        assertEquals(listOf(1_000L), pauses)
    }

    @Test
    fun `a gateway still down after three attempts fails the text`() {
        val transport = ScriptedTransport(status(503))

        assertEquals(listOf(Translation.Failed(CoreMessages.message("translate.error.status", "DeepL", 503, ""))), translate(transport))
        assertEquals(3, transport.bodies.size)
        assertEquals(listOf(1_000L, 2_000L), pauses)
    }

    @Test
    fun `502 and 504 are tried again, a late answer is not`() {
        assertEquals(2, ScriptedTransport(status(502), ok).also { translate(it) }.bodies.size)
        assertEquals(2, ScriptedTransport(status(504), ok).also { translate(it) }.bodies.size)
        val late = ScriptedTransport({ throw SocketTimeoutException() }, ok)
        assertEquals(listOf(Translation.Failed(CoreMessages.message("translate.error.timeout", "DeepL"))), translate(late))
        assertEquals(1, late.bodies.size, "the same request would be as late again")
        assertEquals(listOf(1_000L, 1_000L), pauses, "only the gateways waited")
    }

    @Test
    fun `a 503 between two answers is still tried again`() {
        val transport = ScriptedTransport(ok, status(503), ok)

        val result = translate(transport, oneByOne, "Hello", "World")

        assertTrue(result.all { it is Translation.Done }, "$result")
        assertEquals(3, transport.bodies.size)
        assertEquals(listOf(1_000L), pauses)
    }

    @Test
    fun `a refused key, a bad request, a missing page, a server error or an unreachable engine are not tried again`() {
        for (code in listOf(400, 401, 403, 404, 500)) {
            val transport = ScriptedTransport(status(code), ok)
            assertTrue(translate(transport).single() is Translation.Failed, "HTTP $code")
            assertEquals(1, transport.bodies.size, "HTTP $code")
        }
        val unreachable = ScriptedTransport({ throw IOException("refused") }, ok)
        assertTrue(translate(unreachable).single() is Translation.Failed)
        assertEquals(1, unreachable.bodies.size)
        assertEquals(emptyList<Long>(), pauses)
    }

    @Test
    fun `the wait Retry-After asks for is kept, up to thirty seconds`() {
        translate(ScriptedTransport(status(429, "2"), ok))
        translate(ScriptedTransport(status(503, " 120 "), ok))
        translate(ScriptedTransport(status(429, "Wed, 21 Oct 2026 07:28:00 GMT"), ok))
        translate(ScriptedTransport(status(429, "-1"), ok))

        assertEquals(listOf(2_000L, 30_000L, 1_000L, 1_000L), pauses)
    }

    @Test
    fun `a cancellation during the wait stops the translation`() {
        class Cancelled : RuntimeException()
        val transport = ScriptedTransport(status(429), ok)
        val provider = HttpTranslationProvider(oneByOne, "k", transport).apply { pause = Pause { _, cancellation -> cancellation?.check() } }
        // The first check, before the text goes out, lets it through; the one during the wait stops it.
        var checks = 0
        val cancelDuringTheWait = Cancellation { if (++checks > 1) throw Cancelled() }

        assertThrows(Cancelled::class.java) {
            provider.translate(TranslationRequest(listOf("Hello"), "en", "fr"), cancelDuringTheWait)
        }
        assertEquals(1, transport.bodies.size, "never sent again")
    }

    @Test
    fun `the real pause gives up as soon as the translation is cancelled`() {
        class Cancelled : RuntimeException()
        var checks = 0
        val started = System.nanoTime()

        assertThrows(Cancelled::class.java) { Pause.SLEEP.pause(60_000, Cancellation { if (++checks == 2) throw Cancelled() }) }
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5), "stopped within a slice, not after a minute")
    }

    @Test
    fun `a batch the engine refuses is split until the text it chokes on fails alone`() {
        val transport = object : HttpTransport {
            val sizes = mutableListOf<Int>()
            override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
                val texts = JsonParser.parseString(body).asJsonObject["text"].asJsonArray.map { it.asString }
                sizes += texts.size
                if ("bad" in texts) return HttpTransport.Response(400, "unsupported")
                return HttpTransport.Response(200, texts.joinToString(",", """{"translations":[""", "]}") { """{"text":"FR $it"}""" })
            }
        }

        val result = translate(transport, deepl, "a", "b", "bad", "d")

        val by = TranslatedBy("DeepL", false)
        assertEquals(
            listOf(
                Translation.Done("FR a", by), Translation.Done("FR b", by),
                Translation.Failed(CoreMessages.message("translate.error.status", "DeepL", 400, "unsupported")), Translation.Done("FR d", by)
            ),
            result
        )
        assertEquals(listOf(4, 2, 2, 1, 1), transport.sizes, "the batch, its halves, then the failing half text by text")
        assertEquals(emptyList<Long>(), pauses, "a refused request is not tried again as it is")
    }

    @Test
    fun `an engine that never answers in time costs a bounded number of requests`() {
        val transport = ScriptedTransport({ throw SocketTimeoutException() })
        val texts = (1..20).map { "text $it" }.toTypedArray()

        val result = translate(transport, deepl, *texts)

        assertTrue(result.all { it is Translation.Failed })
        assertEquals(39, transport.bodies.size, "each request of the split sent once, never again as it was")
        assertEquals(emptyList<Long>(), pauses, "a late answer is not waited for again")
    }

    @Test
    fun `the wait for an answer grows with the texts of the request`() {
        val single = ScriptedTransport(ok)
        translate(single)
        val batch = ScriptedTransport({ HttpTransport.Response(200, """{"translations":[{"text":"a"},{"text":"b"},{"text":"c"}]}""") })
        translate(batch, deepl, "a", "b", "c")
        val fixed = ScriptedTransport(ok)
        translate(fixed, oneByOne.copy(readTimeout = Duration.ofSeconds(15)))

        assertEquals(listOf(Duration.ofSeconds(62)), single.timeouts)
        assertEquals(listOf(Duration.ofSeconds(66)), batch.timeouts)
        assertEquals(listOf(Duration.ofSeconds(15)), fixed.timeouts, "an engine's own timeout wins")
        assertEquals(Duration.ofMinutes(5), HttpTranslationProvider.readTimeoutFor(500))
    }
}
