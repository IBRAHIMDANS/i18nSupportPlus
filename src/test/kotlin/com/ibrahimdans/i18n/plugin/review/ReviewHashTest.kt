package com.ibrahimdans.i18n.plugin.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewHashTest {

    @Test
    fun `the hash is sha256 cut to 16 hex digits`() {
        // Checked against `printf 'Panier' | sha256sum`: a file written by one version reads the same in the next.
        val hash = ReviewHash.of("Panier")
        assertTrue(Regex("sha256:[0-9a-f]{16}").matches(hash), hash)
        assertEquals("sha256:3a4ec4cdb9b30670", hash)
    }

    @Test
    fun `CRLF and LF give the same hash`() {
        assertEquals(ReviewHash.of("Line one\nLine two"), ReviewHash.of("Line one\r\nLine two"))
    }

    @Test
    fun `a decomposed accent gives the same hash as the composed one`() {
        val composed = "Créer"
        val decomposed = "Créer"
        assertNotEquals(composed, decomposed)
        assertEquals(ReviewHash.of(composed), ReviewHash.of(decomposed))
    }

    @Test
    fun `a trailing space is a change`() {
        assertNotEquals(ReviewHash.of("Save"), ReviewHash.of("Save "))
    }

    @Test
    fun `a renamed placeholder is a change`() {
        assertNotEquals(ReviewHash.of("Hello {{name}}"), ReviewHash.of("Hello {{user}}"))
    }
}
