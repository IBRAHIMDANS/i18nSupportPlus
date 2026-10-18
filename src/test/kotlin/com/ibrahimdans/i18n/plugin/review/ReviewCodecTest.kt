package com.ibrahimdans.i18n.plugin.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewCodecTest {

    private val canonical = """
        {
          "version": 1,
          "reviews": {
            "common:cart.title": {
              "status": "approved",
              "hash": "sha256:3f2a9c0e1b7d4a55",
              "by": "Alice Martin <alice@example.com>",
              "at": "2026-10-11T14:02:11Z",
              "origin": "machine:DeepL",
              "thread": [
                {
                  "id": "r1",
                  "kind": "request_change",
                  "by": "Alice Martin <alice@example.com>",
                  "at": "2026-10-11T09:12:00Z",
                  "text": "« Panier » plutôt que « Chariot »"
                },
                {
                  "id": "r2",
                  "kind": "approve",
                  "by": "Alice Martin <alice@example.com>",
                  "at": "2026-10-11T14:02:11Z",
                  "text": ""
                }
              ]
            },
            "translation:menu.home": {
              "status": "to_review",
              "hash": "sha256:9b1e0d77c2aa4e10",
              "by": "Bob <bob@example.com>",
              "at": "2026-10-11T15:30:00Z",
              "origin": "machine:Ollama (local)",
              "thread": []
            }
          }
        }

    """.trimIndent()

    @Test
    fun `a file written by the codec reads and writes back unchanged`() {
        assertEquals(canonical, ReviewCodec.write(ReviewCodec.read(canonical)))
    }

    @Test
    fun `the file reads into the model`() {
        val reviews = ReviewCodec.read(canonical)

        assertEquals(setOf(ReviewKey("common", "cart.title"), ReviewKey("translation", "menu.home")), reviews.keys)
        val cart = reviews.getValue(ReviewKey("common", "cart.title"))
        assertEquals(ReviewState.APPROVED, cart.state)
        assertEquals("machine:DeepL", cart.origin)
        assertEquals(listOf(ReviewEventKind.REQUEST_CHANGE, ReviewEventKind.APPROVE), cart.thread.map { it.kind })
    }

    @Test
    fun `keys are written sorted, whatever order they were added in`() {
        val review = Review(ReviewState.TO_REVIEW, "sha256:0000000000000000", "Bob", "T")
        val keys = listOf(ReviewKey("translation", "z"), ReviewKey("common", "b"), ReviewKey("common", "a"))

        val forward = ReviewCodec.write(keys.associateWith { review })
        val backward = ReviewCodec.write(keys.reversed().associateWith { review })

        assertEquals(forward, backward)
        val order = listOf("common:a", "common:b", "translation:z").map { forward.indexOf("\"$it\"") }
        assertEquals(order.sorted(), order, forward)
    }

    @Test
    fun `the output ends with a newline and keeps HTML characters as they are`() {
        val text = ReviewCodec.write(mapOf(ReviewKey("ns", "k") to Review(ReviewState.APPROVED, "h", "A <a@b.c>", "T")))
        assertTrue(text.endsWith("}\n"), text)
        assertTrue("\"A <a@b.c>\"" in text, text)
    }

    @Test
    fun `an absent or empty file holds no review`() {
        assertEquals(emptyMap<ReviewKey, Review>(), ReviewCodec.read(null))
        assertEquals(emptyMap<ReviewKey, Review>(), ReviewCodec.read(""))
        assertEquals(emptyMap<ReviewKey, Review>(), ReviewCodec.read("  \n"))
        assertTrue(ReviewCodec.decode("").readable, "an empty file may be written")
    }

    @Test
    fun `an unreadable file holds no review and must not be overwritten`() {
        val conflicted = canonical.replace("    \"translation:menu.home\"", "<<<<<<< HEAD\n    \"translation:menu.home\"")
        listOf("{", "not json", "[1, 2]", "\"text\"", conflicted, """{"version": 2, "reviews": {}}""").forEach {
            val decoded = ReviewCodec.decode(it)
            assertEquals(emptyMap<ReviewKey, Review>(), decoded.reviews, it)
            assertFalse(decoded.readable, it)
        }
    }

    @Test
    fun `a file written before the source reads without one and writes back byte for byte`() {
        val reviews = ReviewCodec.read(canonical)

        reviews.values.forEach { assertNull(it.source) }
        val written = ReviewCodec.write(reviews)
        assertEquals(canonical, written)
        assertFalse("sourceLocale" in written || "sourceHash" in written, "no empty source field may be written")
    }

    private val withSource = """
        {
          "version": 1,
          "reviews": {
            "common:cart.title": {
              "status": "approved",
              "hash": "sha256:3f2a9c0e1b7d4a55",
              "by": "Alice",
              "at": "2026-10-15T14:02:11Z",
              "origin": "manual",
              "thread": [],
              "sourceLocale": "en",
              "sourceHash": "sha256:9b1e0d77c2aa4e10"
            }
          }
        }

    """.trimIndent()

    @Test
    fun `the source is read and written last in the entry, still in version 1`() {
        val review = ReviewCodec.read(withSource).getValue(ReviewKey("common", "cart.title"))

        assertEquals(ReviewSource("en", "sha256:9b1e0d77c2aa4e10"), review.source)
        assertEquals(withSource, ReviewCodec.write(mapOf(ReviewKey("common", "cart.title") to review)))
        assertEquals(1, ReviewCodec.VERSION)
    }

    @Test
    fun `a source missing its locale or its hash is unknown`() {
        val text = """
            {"version": 1, "reviews": {
              "ns:no-locale": {"status": "approved", "hash": "h", "sourceHash": "s"},
              "ns:no-hash": {"status": "approved", "hash": "h", "sourceLocale": "en"}
            }}
        """.trimIndent()

        assertEquals(listOf(null, null), ReviewCodec.read(text).values.map { it.source })
    }

    @Test
    fun `the properties of a later version are written back as they were read`() {
        val later = withSource.replace(
            "      \"sourceHash\": \"sha256:9b1e0d77c2aa4e10\"\n",
            """
            |      "sourceHash": "sha256:9b1e0d77c2aa4e10",
            |      "assignee": "Carol",
            |      "labels": [
            |        "urgent",
            |        1.50
            |      ],
            |      "meta": {
            |        "pinned": true,
            |        "note": null
            |      }
            |
            """.trimMargin()
        )
        val review = ReviewCodec.read(later).getValue(ReviewKey("common", "cart.title"))

        assertEquals(listOf("assignee", "labels", "meta"), review.extra.keys.toList())
        assertEquals(later, ReviewCodec.write(mapOf(ReviewKey("common", "cart.title") to review)))
        // A decision of this version keeps them too.
        val approved = ReviewEdits.decide(mapOf(ReviewKey("common", "cart.title") to review), ReviewKey("common", "cart.title"), ReviewState.APPROVED, "Panier", "Bob", "T")
        assertTrue("\"assignee\": \"Carol\"" in ReviewCodec.write(approved))
    }

    private val laterEvents = canonical.replace(
        "          \"text\": \"« Panier » plutôt que « Chariot »\"\n",
        """
        |          "text": "« Panier » plutôt que « Chariot »",
        |          "suggestion": "Panier",
        |          "resolved": false,
        |          "anchor": {
        |            "start": 0,
        |            "end": 7,
        |            "note": null
        |          }
        |
        """.trimMargin()
    )

    @Test
    fun `the properties of a later version on a thread entry are written back as they were read`() {
        val review = ReviewCodec.read(laterEvents).getValue(ReviewKey("common", "cart.title"))

        // `suggestion` is this version's own now; the others stay as they were read.
        assertEquals("Panier", review.thread.first().suggestion)
        assertEquals(listOf("resolved", "anchor"), review.thread.first().extra.keys.toList())
        assertEquals(emptyMap<String, String>(), review.thread.last().extra)
        assertEquals(laterEvents, ReviewCodec.write(ReviewCodec.read(laterEvents)))
        assertEquals(1, ReviewCodec.VERSION)
    }

    @Test
    fun `an entry added to the thread leaves the others' unknown properties untouched`() {
        val key = ReviewKey("common", "cart.title")
        val reviews = ReviewCodec.read(laterEvents)

        val commented = ReviewEdits.comment(reviews, key, "Panier", "Bob", "T3", "OK")
        val thread = commented.getValue(key).thread

        assertEquals(reviews.getValue(key).thread, thread.dropLast(1))
        assertEquals(emptyMap<String, String>(), thread.last().extra)
        val written = ReviewCodec.write(commented)
        assertTrue("\"suggestion\": \"Panier\"" in written, written)
        assertTrue("\"resolved\": false" in written, written)
    }

    @Test
    fun `a suggestion is read and written after the text, still in version 1`() {
        val withSuggestion = canonical.replace(
            "          \"text\": \"« Panier » plutôt que « Chariot »\"\n",
            "          \"text\": \"« Panier » plutôt que « Chariot »\",\n          \"suggestion\": \"Panier\"\n"
        )
        val thread = ReviewCodec.read(withSuggestion).getValue(ReviewKey("common", "cart.title")).thread

        assertEquals(listOf("Panier", null), thread.map { it.suggestion })
        assertEquals(emptyMap<String, String>(), thread.first().extra)
        assertEquals(withSuggestion, ReviewCodec.write(ReviewCodec.read(withSuggestion)))
        assertEquals(1, ReviewCodec.VERSION)
    }

    @Test
    fun `a file written before the suggestion reads without one and writes none`() {
        val written = ReviewCodec.write(ReviewCodec.read(canonical))

        assertEquals(canonical, written)
        assertFalse("suggestion" in written, "no empty suggestion may be written")
    }

    @Test
    fun `an entry missing its status or hash is skipped, the others kept`() {
        val text = """
            {"version": 1, "reviews": {
              "ns:ok": {"status": "approved", "hash": "h"},
              "ns:no-status": {"hash": "h"},
              "ns:bad-status": {"status": "conflict", "hash": "h"},
              "ns:no-hash": {"status": "approved"},
              "no-namespace": {"status": "approved", "hash": "h"}
            }}
        """.trimIndent()

        assertEquals(setOf(ReviewKey("ns", "ok")), ReviewCodec.read(text).keys)
    }
}
