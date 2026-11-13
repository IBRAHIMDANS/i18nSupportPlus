package com.ibrahimdans.i18n.plugin.utils

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Export → import round trips of the CSV and XLIFF codecs, on generated keys and values: what a
 * translator gets back from a file the plugin wrote must be what the project holds.
 *
 * The alphabet leans on the characters each format escapes (separators, quotes, line breaks,
 * markup, CDATA end, emoji, accents). XML 1.0 has no room for the other control characters, so
 * they are left out. The seed is fixed: a failure replays the same cases.
 */
class CodecPropertyTest {

    @OptIn(ExperimentalKotest::class)
    private val config = PropTestConfig(seed = 20261113L, iterations = 500)

    private val locales = listOf("en", "fr")

    private val tricky = listOf(",", "\"", "\n", "\r", "&", "<", ">", "]]>", "'", "\t", " ", "é", "😀", "{", "}", "a", "b", "Z", "0")

    /**
     * A unit id is an attribute: XML turns its tabs and line breaks into spaces, and parse skips a
     * blank id. Keys carry neither in practice.
     */
    private val keyAlphabet = tricky - listOf("\n", "\r", "\t", " ")

    private fun text(alphabet: List<String>, minSize: Int) =
        Arb.string(minSize..12, Arb.of(alphabet.flatMap { s -> s.codePoints().toArray().map(::Codepoint) }))

    private fun translations(keys: Arb<String>, values: Arb<String>) =
        Arb.map(keys, Arb.map(Arb.of(locales), values, minSize = 0, maxSize = locales.size), minSize = 0, maxSize = 8)

    @Test
    fun `a CSV export reads back cell for cell`() {
        runBlocking {
            checkAll(config, translations(text(tricky, 1), text(tricky, 0))) { t ->
                val expected = listOf(listOf(CsvTranslationCodec.KEY_COLUMN) + locales) +
                    t.keys.sorted().map { key -> listOf(key) + locales.map { t.getValue(key)[it].orEmpty() } }

                assertEquals(expected, CsvTranslationCodec.parse(CsvTranslationCodec.encode(locales, t)))
            }
        }
    }

    @Test
    fun `an XLIFF export reads back the target of every unit`() {
        runBlocking {
            checkAll(config, translations(text(keyAlphabet, 1), text(tricky - "\r", 0))) { t ->
                assertXliffRoundTrip(t)
            }
        }
    }

    /**
     * Smallest case: `fr = "a\r\nb"` comes back as `"a\nb"`, and a lone `"\r"` as `"\n"`. XML
     * normalizes the line breaks of text content, and encode writes `\r` as is instead of `&#13;`.
     * The import plan then sees a changed value and rewrites it.
     */
    @Test
    @Disabled("CADRAGE-XLIFF-CARRIAGE-RETURN: encode writes \\r raw, XML parsing turns it into \\n")
    fun `an XLIFF export keeps the carriage returns of a value`() {
        runBlocking {
            checkAll(config, translations(text(keyAlphabet, 1), text(tricky, 0))) { t ->
                assertXliffRoundTrip(t)
            }
        }
    }

    /** [t] exported from `en` to `fr` reads back as one row per key with an `en` value, its `fr` value next to it. */
    private fun assertXliffRoundTrip(t: Map<String, Map<String, String>>) {
        val xliff = XliffTranslationCodec.encode("en", "fr", t)
        val units = t.keys.sorted().filter { !t.getValue(it)["en"].isNullOrEmpty() }
        // Nothing to translate: parse refuses a document without units.
        if (units.isEmpty()) {
            assertThrows(IllegalArgumentException::class.java) { XliffTranslationCodec.parse(xliff) }
            return
        }
        val expected = listOf(listOf(CsvTranslationCodec.KEY_COLUMN, "fr")) + units.map { listOf(it, t.getValue(it)["fr"].orEmpty()) }
        assertEquals(expected, XliffTranslationCodec.parse(xliff))
    }
}
