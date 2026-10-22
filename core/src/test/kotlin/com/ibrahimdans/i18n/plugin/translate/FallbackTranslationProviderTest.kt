package com.ibrahimdans.i18n.plugin.translate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The fallback chain, and how a [Cancellation] reaches every engine of it. No platform, no network. */
class FallbackTranslationProviderTest {

    private class Cancelled : RuntimeException()

    /** An engine that answers every text with [answer], and remembers what it was asked. */
    private class Engine(private val answer: (String) -> Translation) : TranslationProvider {
        val asked = mutableListOf<List<String>>()
        var cancellation: Cancellation? = null

        override fun translate(request: TranslationRequest, cancellation: Cancellation?): List<Translation> {
            asked += request.texts
            this.cancellation = cancellation
            return request.texts.map { text ->
                cancellation?.check()
                answer(text)
            }
        }
    }

    private val request = TranslationRequest(listOf("one", "two"), "en", "fr")

    @Test
    fun `the next engine is asked only for what failed`() {
        val first = Engine { if (it == "one") Translation.Done("un") else Translation.Failed("down") }
        val second = Engine { Translation.Done("deux") }

        val result = FallbackTranslationProvider(listOf(first, second)).translate(request)

        assertEquals(listOf(Translation.Done("un"), Translation.Done("deux")), result)
        assertEquals(listOf(listOf("two")), second.asked)
    }

    @Test
    fun `a text failing everywhere keeps the last reason`() {
        val result = FallbackTranslationProvider(listOf(Engine { Translation.Failed("first") }, Engine { Translation.Failed("last") }))
            .translate(request.copy(texts = listOf("one")))

        assertEquals(listOf(Translation.Failed("last")), result)
    }

    @Test
    fun `every engine checks the same cancellation`() {
        val first = Engine { Translation.Failed("down") }
        val second = Engine { Translation.Done("x") }
        val cancellation = Cancellation { }

        FallbackTranslationProvider(listOf(first, second)).translate(request, cancellation)

        assertSame(cancellation, first.cancellation)
        assertSame(cancellation, second.cancellation)
    }

    @Test
    fun `a cancellation on the second text stops the whole chain`() {
        val translated = mutableListOf<String>()
        val first = Engine { translated += it; Translation.Failed("down") }
        val second = Engine { Translation.Done("x") }
        var checks = 0
        // Checked once by the chain before the first engine, then once per text by the engine.
        val cancelOnSecondText = Cancellation { if (++checks == 3) throw Cancelled() }

        assertThrows(Cancelled::class.java) {
            FallbackTranslationProvider(listOf(first, second)).translate(request, cancelOnSecondText)
        }
        assertEquals(listOf("one"), translated, "the second text is never translated")
        assertEquals(listOf(listOf("one", "two")), first.asked, "the first engine got the request")
        assertEquals(emptyList<List<String>>(), second.asked, "the second never did")
    }
}
