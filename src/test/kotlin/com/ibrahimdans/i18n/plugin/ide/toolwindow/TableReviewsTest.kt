package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewHash
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ShownReview
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** [TableReviews] on its own: what a review means for a cell, and the source it is read against. */
class TableReviewsTest {

    private val approved = Review(ReviewState.APPROVED, ReviewHash.of("Accueil"), "Alice", "2026-10-11T14:02:11Z")

    private val reviews = TableReviews(
        mapOf("fr" to mapOf(ReviewKey("translation", "menu.home") to approved)),
        "translation",
        referenceLocale = "en",
    )

    @Test
    fun `OFF knows no review`() {
        assertNull(TableReviews.OFF.reviewOf("menu.home", "fr"))
        assertEquals(ShownReview.NONE, TableReviews.OFF.shownOf("menu.home", "fr", "Accueil"))
    }

    @Test
    fun `a key without a namespace is read under the default one`() {
        assertEquals(ReviewKey("translation", "menu.home"), reviews.reviewKey("menu.home"))
        assertSame(approved, reviews.reviewOf("menu.home", "fr"))
    }

    @Test
    fun `the source is hashed once per key and text, and an edit of it is seen`() {
        val first = reviews.sourceOf("menu.home", "Home")
        assertSame(first, reviews.sourceOf("menu.home", "Home"))
        assertEquals(ReviewSource.of("en", "Start"), reviews.sourceOf("menu.home", "Start"))
        assertNull(reviews.sourceOf("menu.home", ""))
    }
}
