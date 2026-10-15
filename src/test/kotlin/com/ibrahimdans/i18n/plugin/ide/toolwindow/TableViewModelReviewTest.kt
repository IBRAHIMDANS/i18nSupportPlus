package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewHash
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ShownReview
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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
    fun `with reviews the combo adds the three review filters at the end`() {
        assertEquals(StatusFilter.entries.toList(), viewModel.statusFilters(reviews))
        assertEquals(
            listOf(StatusFilter.TO_REVIEW, StatusFilter.SOURCE_CHANGED, StatusFilter.CHANGES_REQUESTED),
            StatusFilter.entries.filter { it.readsReviews }
        )
    }

    @Test
    fun `visibleRows hands the reviews to the status filter`() {
        val visible = viewModel.visibleRows(rows, "", NamespaceFilter.All, StatusFilter.CHANGES_REQUESTED, listOf("de"), reviews)
        assertEquals(listOf("menu.about"), visible.map { it.key })
    }

    // ── Source changed ────────────────────────────────────────────────────────

    /** fr and de approved on their value, decided against the English [source]. */
    private fun approvedOn(source: String) = TableReviews(
        mapOf(
            "fr" to mapOf(ReviewKey("translation", "menu.home") to review(ReviewState.APPROVED, "Accueil").copy(source = ReviewSource.of("en", source))),
            "de" to mapOf(ReviewKey("translation", "menu.home") to review(ReviewState.APPROVED, "Start").copy(source = ReviewSource.of("en", source))),
            // The reference value approved as it reads now, with a source of its own that differs: never compared.
            "en" to mapOf(ReviewKey("translation", "menu.home") to review(ReviewState.APPROVED, "Home page").copy(source = ReviewSource.of("en", source))),
        ),
        "translation",
        referenceLocale = "en",
    )

    private val homeRow = TranslationRow("menu.home", mapOf("en" to "Home page", "fr" to "Accueil", "de" to "Start"))

    @Test
    fun `an approval whose source changed since shows in the Source changed and To review filters`() {
        val stale = approvedOn("Home")

        assertEquals(ShownReview.SOURCE_CHANGED, stale.shownOf(homeRow, "fr"))
        assertEquals(listOf(homeRow), viewModel.filterByStatus(StatusFilter.SOURCE_CHANGED, listOf(homeRow), listOf("en", "fr"), stale))
        assertEquals(listOf(homeRow), viewModel.filterByStatus(StatusFilter.TO_REVIEW, listOf(homeRow), listOf("en", "fr"), stale))
        assertTrue(ShownReview.SOURCE_CHANGED in TableReviews.TO_REVIEW_STATES)
    }

    @Test
    fun `a source unchanged since the decision keeps the approval`() {
        val fresh = approvedOn("Home page")

        assertEquals(ShownReview.APPROVED, fresh.shownOf(homeRow, "fr"))
        assertTrue(viewModel.filterByStatus(StatusFilter.SOURCE_CHANGED, listOf(homeRow), listOf("en", "fr", "de"), fresh).isEmpty())
        assertTrue(viewModel.filterByStatus(StatusFilter.TO_REVIEW, listOf(homeRow), listOf("en", "fr", "de"), fresh).isEmpty())
    }

    @Test
    fun `the Source changed filter reads the shown locales only, the reference read even when hidden`() {
        val stale = approvedOn("Home")

        assertEquals(listOf(homeRow), viewModel.filterByStatus(StatusFilter.SOURCE_CHANGED, listOf(homeRow), listOf("de"), stale), "en is hidden, its source still read")
        assertTrue(viewModel.filterByStatus(StatusFilter.SOURCE_CHANGED, listOf(homeRow), listOf("en"), stale).isEmpty(), "fr and de are hidden")
    }

    @Test
    fun `the reference locale itself is never stale`() {
        assertEquals(ShownReview.APPROVED, approvedOn("Home").shownOf(homeRow, "en"))
    }

    @Test
    fun `approving again on the current source is no longer stale`() {
        val reapproved = TableReviews(
            mapOf("fr" to mapOf(ReviewKey("translation", "menu.home") to review(ReviewState.APPROVED, "Accueil").copy(source = ReviewSource.of("en", "Home page")))),
            "translation",
            referenceLocale = "en",
        )

        assertEquals(ShownReview.APPROVED, reapproved.shownOf(homeRow, "fr"))
    }

    @Test
    fun `a value edited since the decision is to review, not stale`() {
        val edited = homeRow.copy(values = homeRow.values + ("fr" to "Page d'accueil"))

        assertEquals(ShownReview.CHANGED_SINCE_APPROVAL, approvedOn("Home").shownOf(edited, "fr"))
    }

    @Test
    fun `a review without source, or a module without reference locale, is never stale`() {
        val noSource = TableReviews(
            mapOf("fr" to mapOf(ReviewKey("translation", "menu.home") to review(ReviewState.APPROVED, "Accueil"))), "translation", referenceLocale = "en",
        )
        val noReference = TableReviews(
            mapOf("fr" to mapOf(ReviewKey("translation", "menu.home") to review(ReviewState.APPROVED, "Accueil").copy(source = ReviewSource.of("en", "Home")))),
            "translation",
        )

        assertEquals(ShownReview.APPROVED, noSource.shownOf(homeRow, "fr"))
        assertEquals(ShownReview.APPROVED, noReference.shownOf(homeRow, "fr"))
        assertNull(noReference.sourceOf("menu.home", "Home page"))
    }

    @Test
    fun `the source hash is kept per key and follows an edit of the source`() {
        val stale = approvedOn("Home")

        val first = stale.sourceOf("menu.home", "Home page")
        assertSame(first, stale.sourceOf("menu.home", "Home page"), "hashed once per key and text")
        assertEquals(ReviewSource.of("en", "Home page"), first)
        assertEquals(ShownReview.APPROVED, stale.shownOf("menu.home", "fr", "Accueil", "Home"), "the source edited back to its old text")
        assertNull(stale.sourceOf("menu.home", ""), "an empty source is no source")
    }

    @Test
    fun `without reviews nothing is stale and no Source changed filter is offered`() {
        assertFalse(StatusFilter.SOURCE_CHANGED in viewModel.statusFilters(TableReviews.OFF))
        assertEquals(ShownReview.NONE, TableReviews.OFF.shownOf(homeRow, "fr"))
        assertNull(TableReviews.OFF.sourceOf("menu.home", "Home page"))
    }
}
