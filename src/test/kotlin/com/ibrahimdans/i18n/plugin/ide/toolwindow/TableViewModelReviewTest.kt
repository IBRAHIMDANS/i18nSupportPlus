package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewHash
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ShownReview
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The review filters of the table and the [TableReviews] they read, without a project. */
class TableViewModelReviewTest {

    private val viewModel = TableViewModel()

    private fun review(state: ReviewState, value: String) = Review(state, ReviewHash.of(value), "Alice", "2026-10-11T14:02:11Z")

    private val rows = listOf(
        TranslationRow("menu.home", mapOf("en" to "Home", "fr" to "Accueil", "de" to "Start")),
        TranslationRow("menu.about", mapOf("en" to "About", "fr" to "À propos", "de" to "Über")),
        TranslationRow("common:cart.title", mapOf("en" to "Cart", "fr" to "Panier")),
        TranslationRow("menu.untouched", mapOf("en" to "Untouched", "fr" to "Intact")),
    )

    /**
     * fr: `menu.home` to review, `menu.about` approved on its value, `common:cart.title` approved
     * on an older value. de: `menu.about` with changes requested. Default namespace `translation`.
     */
    private val reviews = TableReviews(
        mapOf(
            "fr" to mapOf(
                ReviewKey("translation", "menu.home") to review(ReviewState.TO_REVIEW, "Accueil"),
                ReviewKey("translation", "menu.about") to review(ReviewState.APPROVED, "À propos"),
                ReviewKey("common", "cart.title") to review(ReviewState.APPROVED, "Chariot"),
            ),
            "de" to mapOf(
                ReviewKey("translation", "menu.about") to review(ReviewState.CHANGES_REQUESTED, "Über"),
                ReviewKey("translation", "menu.home") to review(ReviewState.CHANGES_REQUESTED, "Anfang"),
            ),
        ),
        "translation",
    )

    private fun keys(filter: StatusFilter, shown: List<String>) =
        viewModel.filterByStatus(filter, rows, shown, reviews).map { it.key }

    @Test
    fun `TO_REVIEW keeps the keys to review, changed since approval or corrected since a request`() {
        // menu.home: to review in fr, and corrected (Anfang → Start) in de; cart.title: approved
        // on "Chariot", holding "Panier" now. menu.about is approved in fr, requested in de.
        assertEquals(listOf("menu.home", "common:cart.title"), keys(StatusFilter.TO_REVIEW, listOf("en", "fr", "de")))
    }

    @Test
    fun `CHANGES_REQUESTED keeps the keys whose request still names the value shown`() {
        assertEquals(listOf("menu.about"), keys(StatusFilter.CHANGES_REQUESTED, listOf("en", "fr", "de")))
    }

    @Test
    fun `the review filters read the shown locales only`() {
        assertEquals(emptyList<String>(), keys(StatusFilter.CHANGES_REQUESTED, listOf("en", "fr")), "de is hidden")
        assertEquals(listOf("menu.home"), keys(StatusFilter.TO_REVIEW, listOf("de")), "fr is hidden")
        assertEquals(emptyList<String>(), keys(StatusFilter.TO_REVIEW, listOf("en")), "nothing is reviewed in en")
    }

    @Test
    fun `a value changed after its approval falls back to the To review filter`() {
        val approved = TableReviews(mapOf("fr" to mapOf(ReviewKey("translation", "menu.home") to review(ReviewState.APPROVED, "Accueil"))), "translation")
        val before = listOf(TranslationRow("menu.home", mapOf("fr" to "Accueil")))
        val after = listOf(TranslationRow("menu.home", mapOf("fr" to "Page d'accueil")))

        assertTrue(viewModel.filterByStatus(StatusFilter.TO_REVIEW, before, listOf("fr"), approved).isEmpty())
        assertEquals(ShownReview.APPROVED, approved.shownOf("menu.home", "fr", "Accueil"))
        assertEquals(after, viewModel.filterByStatus(StatusFilter.TO_REVIEW, after, listOf("fr"), approved))
        assertEquals(ShownReview.CHANGED_SINCE_APPROVAL, approved.shownOf("menu.home", "fr", "Page d'accueil"))
    }

    @Test
    fun `a locale without value shows no review, whatever is stored`() {
        assertEquals(ShownReview.NONE, reviews.shownOf("menu.home", "fr", ""))
        assertEquals(ShownReview.NONE, reviews.shownOf("menu.home", "fr", null))
    }

    @Test
    fun `a key of a named namespace finds its review, a default one finds it under the default namespace`() {
        assertEquals(ReviewState.APPROVED, reviews.reviewOf("common:cart.title", "fr")?.state)
        assertEquals(ReviewState.TO_REVIEW, reviews.reviewOf("menu.home", "fr")?.state)
        assertNull(reviews.reviewOf("menu.untouched", "fr"))
    }

    @Test
    fun `without reviews the combo offers what it always did and nothing reads as reviewed`() {
        val off = TableReviews.OFF

        assertEquals(
            listOf(StatusFilter.ALL, StatusFilter.MISSING, StatusFilter.BLANK, StatusFilter.ORPHAN, StatusFilter.DUPLICATE),
            viewModel.statusFilters(off)
        )
        assertFalse(off.enabled)
        assertEquals(ShownReview.NONE, off.shownOf("menu.home", "fr", "Accueil"))
        assertTrue(viewModel.filterByStatus(StatusFilter.TO_REVIEW, rows, listOf("fr"), off).isEmpty())
    }

    @Test
    fun `with reviews the combo adds the two review filters at the end`() {
        assertEquals(StatusFilter.entries.toList(), viewModel.statusFilters(reviews))
        assertEquals(listOf(StatusFilter.TO_REVIEW, StatusFilter.CHANGES_REQUESTED), StatusFilter.entries.filter { it.readsReviews })
    }

    @Test
    fun `visibleRows hands the reviews to the status filter`() {
        val visible = viewModel.visibleRows(rows, "", NamespaceFilter.All, StatusFilter.CHANGES_REQUESTED, listOf("de"), reviews)
        assertEquals(listOf("menu.about"), visible.map { it.key })
    }
}
