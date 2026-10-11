package com.ibrahimdans.i18n.plugin.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
