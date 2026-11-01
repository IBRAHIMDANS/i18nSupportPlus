package com.ibrahimdans.i18n.plugin.ide.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The table's row and status types on their own, without a view model. */
class TableRowStatesTest {

    @Test
    fun `only the review filters read reviews`() {
        assertEquals(
            setOf(StatusFilter.TO_REVIEW, StatusFilter.SOURCE_CHANGED, StatusFilter.CHANGES_REQUESTED),
            StatusFilter.entries.filter { it.readsReviews }.toSet(),
        )
    }

    @Test
    fun `a row starts not scanned`() {
        assertEquals(-1, TranslationRow("menu.home", mapOf("en" to "Home")).usageCount)
    }

    @Test
    fun `no source at all keeps the configured namespace name`() {
        assertTrue(carriesNamespaces(emptyList()))
    }
}
