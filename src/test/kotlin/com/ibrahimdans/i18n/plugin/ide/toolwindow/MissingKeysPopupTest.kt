package com.ibrahimdans.i18n.plugin.ide.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [MissingKeysPopup] without showing it: its lines, and how a reference value reads in one. */
class MissingKeysPopupTest {

    private val popup = MissingKeysPopup(emptyMap(), {}, { _, _ -> })

    @Test
    fun `missing keys come first, then the empty ones`() {
        val cell = LocaleStats(
            "fr", total = 4, translated = 1, missing = 2, percent = 25.0,
            missingKeys = listOf("b", "a"), empty = 1, emptyKeys = listOf("c"),
        )
        assertEquals(
            listOf(
                MissingKeysPopup.UntranslatedKey("b", LocaleState.MISSING),
                MissingKeysPopup.UntranslatedKey("a", LocaleState.MISSING),
                MissingKeysPopup.UntranslatedKey("c", LocaleState.EMPTY),
            ),
            popup.lines(cell),
        )
    }

    @Test
    fun `a multi-line reference reads as one line`() {
        assertEquals("Hello dear world", MissingKeysPopup.displayValue("  Hello\n\n  dear \nworld  "))
    }
}
