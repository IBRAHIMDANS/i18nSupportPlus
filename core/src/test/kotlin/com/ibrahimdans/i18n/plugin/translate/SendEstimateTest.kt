package com.ibrahimdans.i18n.plugin.translate

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class SendEstimateTest {

    @Test
    fun `texts and characters are counted per locale and in total`() {
        val estimate = SendEstimate.of(mapOf("fr" to listOf("Save", "Cancel"), "de" to listOf("Save")))

        Assertions.assertEquals(SendEstimate.Volume(2, 10), estimate.byLocale["fr"])
        Assertions.assertEquals(SendEstimate.Volume(1, 4), estimate.byLocale["de"])
        Assertions.assertEquals(3, estimate.texts)
        Assertions.assertEquals(14, estimate.characters)
    }

    @Test
    fun `a placeholder is counted as the masked tag the engine receives`() {
        val text = "Hello {{name}}"
        val estimate = SendEstimate.of(mapOf("fr" to listOf(text)))

        Assertions.assertEquals(PlaceholderMask.mask(text).text.length, estimate.characters)
        Assertions.assertNotEquals(text.length, estimate.characters, "the variable is not counted as written")
    }

    @Test
    fun `characters are code points, an emoji counting once`() {
        Assertions.assertEquals(4, SendEstimate.of(mapOf("fr" to listOf("Hi 👋"))).characters)
    }

    @Test
    fun `a locale with nothing to send is left out`() {
        val estimate = SendEstimate.of(mapOf("fr" to emptyList(), "de" to listOf("Save")))

        Assertions.assertEquals(setOf("de"), estimate.byLocale.keys)
    }

    @Test
    fun `under the threshold the pass sends at once, from it the pass asks`() {
        val under = SendEstimate.of(mapOf("fr" to List(SendEstimate.CONFIRM_FROM_TEXTS - 1) { "Save" }))
        val from = SendEstimate.of(mapOf("fr" to List(SendEstimate.CONFIRM_FROM_TEXTS - 10) { "Save" }, "de" to List(10) { "Save" }))

        Assertions.assertFalse(under.needsConfirmation())
        Assertions.assertTrue(from.needsConfirmation(), "the texts of every locale count together")
        Assertions.assertFalse(SendEstimate.of(emptyMap()).needsConfirmation())
    }
}
