package com.ibrahimdans.i18n.plugin.translate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** *Cancel* ends a pass while the engine has not answered yet, instead of waiting for its answer. */
class HttpTranslationCancelTest {

    private class Cancelled : RuntimeException()

    /** An engine that answers only once [release] opens, recording the thread it was asked on. */
    private class SilentTransport : HttpTransport {
        val asked = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var thread: Thread? = null

        override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response =
            post(url, headers, body, Duration.ZERO)

        override fun post(url: String, headers: Map<String, String>, body: String, readTimeout: Duration): HttpTransport.Response {
            thread = Thread.currentThread()
            asked.countDown()
            release.await(30, TimeUnit.SECONDS)
            return HttpTransport.Response(200, """{"translations":[{"text":"Bonjour"}]}""")
        }
    }

    private val deepl = TranslationEngines.preset("deepl")!!.copy(batch = null)
    private val request = TranslationRequest(listOf("Hello"), "en", "fr")

    @Test
    fun `cancelling while the engine is silent ends the pass at once`() {
        val transport = SilentTransport()
        val cancelled = AtomicBoolean(false)
        val cancellation = Cancellation { if (cancelled.get()) throw Cancelled() }
        Thread { transport.asked.await(); cancelled.set(true) }.start()

        val started = System.nanoTime()
        assertThrows(Cancelled::class.java) { HttpTranslationProvider(deepl, "k", transport).translate(request, cancellation) }
        val waitedMs = (System.nanoTime() - started) / 1_000_000

        transport.release.countDown()
        assertTrue(waitedMs < 5_000, "the pass waited ${waitedMs} ms for an engine that never answered")
    }

    @Test
    fun `an engine answering is read as before when nothing cancels`() {
        val transport = SilentTransport().apply { release.countDown() }

        val translations = HttpTranslationProvider(deepl, "k", transport).translate(request, Cancellation { })

        assertEquals(listOf(Translation.Done("Bonjour", TranslatedBy("DeepL", false))), translations)
    }

    @Test
    fun `without a cancellation the request runs on the caller's thread`() {
        val transport = SilentTransport().apply { release.countDown() }

        HttpTranslationProvider(deepl, "k", transport).translate(request)

        assertSame(Thread.currentThread(), transport.thread)
    }
}
