package com.ibrahimdans.i18n.plugin.translate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** How an engine of the presets file is read when its batch is written in full, in part, or not at all. */
class TranslationEnginesTest {

    private fun batchOf(batch: String): BatchConfig? =
        TranslationEngines.parse("""[{"id": "engine", "url": "https://example.test", "batch": $batch}]""").single().first.batch

    @Test
    fun `a complete batch is read`() {
        assertEquals(
            BatchConfig("{texts}", "translations", "text", jsonText = true, maxSize = 50),
            batchOf("""{"body": "{texts}", "listPath": "translations", "itemField": "text", "jsonText": true, "maxSize": 50}""")
        )
    }

    @Test
    fun `a batch without its body leaves the engine one text per request`() {
        assertNull(batchOf("""{"listPath": "translations", "maxSize": 50}"""))
    }

    @Test
    fun `a batch without its list path or a size above one leaves the engine one text per request`() {
        assertNull(batchOf("""{"body": "{texts}", "maxSize": 50}"""))
        assertNull(batchOf("""{"body": "{texts}", "listPath": "translations"}"""))
        assertNull(batchOf("""{"body": "{texts}", "listPath": "translations", "maxSize": 1}"""))
    }

    @Test
    fun `an engine without a batch has none`() {
        assertNull(TranslationEngines.parse("""[{"id": "engine"}]""").single().first.batch)
    }
}
