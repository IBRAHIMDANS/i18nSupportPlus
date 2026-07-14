package com.ibrahimdans.i18n.plugin.ide.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for the single-line display normalization used by the Table View
 * cell renderer. Raw values keep their formatting in the cell tooltip;
 * only the rendered text is normalized.
 */
class DisplayValueTest {

    @Test
    fun collapsesWhitespaceRunsIncludingNewlines() {
        assertEquals(
            "[ { \"files\": \"*.{js,ts}\", \"options\": {",
            displayValue("[\n    {\n        \"files\": \"*.{js,ts}\",\n        \"options\": {")
        )
    }

    @Test
    fun trimsLeadingAndTrailingWhitespace() {
        assertEquals("hello world", displayValue("  hello   world \n"))
    }

    @Test
    fun leavesShortSingleLineValuesUntouched() {
        assertEquals("Bonjour", displayValue("Bonjour"))
    }

    @Test
    fun truncatesLongValuesWithEllipsis() {
        val long = "a".repeat(DISPLAY_VALUE_MAX_LENGTH + 50)
        val display = displayValue(long)
        assertEquals(DISPLAY_VALUE_MAX_LENGTH + 1, display.length)
        assertEquals("…", display.last().toString())
    }

    @Test
    fun truncationCountsCollapsedLengthNotRawLength() {
        // Raw is longer than the limit, but collapses to a short string: no ellipsis.
        val raw = "a" + " ".repeat(DISPLAY_VALUE_MAX_LENGTH * 2) + "b"
        assertEquals("a b", displayValue(raw))
    }

    @Test
    fun emptyAndBlankValuesRenderEmpty() {
        assertEquals("", displayValue(""))
        assertEquals("", displayValue("   \n\t"))
    }
}
