package com.ibrahimdans.i18n.plugin.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReviewModelTest {

    private val value = "Panier"
    private val key = ReviewKey("common", "cart.title")

    private fun review(state: ReviewState, on: String = value) =
        Review(state, ReviewHash.of(on), "Alice", "2026-10-11T09:00:00Z")

    // --- shown: the truth table of the framing ---

    @Test
    fun `no review shows nothing`() {
        assertEquals(ShownReview.NONE, shown(null, value))
    }

    @Test
    fun `to review stays to review whatever the value`() {
        assertEquals(ShownReview.TO_REVIEW, shown(review(ReviewState.TO_REVIEW), value))
        assertEquals(ShownReview.TO_REVIEW, shown(review(ReviewState.TO_REVIEW), "Chariot"))
    }

    @Test
    fun `an approval holds on the value it was made on`() {
        assertEquals(ShownReview.APPROVED, shown(review(ReviewState.APPROVED), value))
    }

    @Test
    fun `an approval falls when the value changes`() {
        assertEquals(ShownReview.CHANGED_SINCE_APPROVAL, shown(review(ReviewState.APPROVED), "Panier "))
    }

    @Test
    fun `a change request holds while the value is unchanged`() {
        assertEquals(ShownReview.CHANGES_REQUESTED, shown(review(ReviewState.CHANGES_REQUESTED), value))
    }

    @Test
    fun `a change request becomes to review again once the value is fixed`() {
        assertEquals(ShownReview.FIXED_SINCE_REQUEST, shown(review(ReviewState.CHANGES_REQUESTED, "Chariot"), value))
    }

    @Test
    fun `a review whose value is gone is an orphan, hidden`() {
        assertEquals(ShownReview.NONE, shown(review(ReviewState.APPROVED), null))
    }

    // --- ReviewKey ---

    @Test
    fun `a key of the default namespace gets it written out`() {
        assertEquals(ReviewKey("translation", "menu.home"), ReviewKey.fromSpelling("menu.home", "translation"))
        assertEquals("translation:menu.home", ReviewKey.fromSpelling("menu.home", "translation").stored)
    }

    @Test
    fun `a key of another namespace keeps it`() {
        assertEquals(ReviewKey("common", "cart.title"), ReviewKey.fromSpelling("common:cart.title", "translation"))
    }

    @Test
    fun `a Laravel package namespace is read whole`() {
        assertEquals(
            ReviewKey("courier::messages", "welcome"),
            ReviewKey.fromSpelling("courier::messages:welcome", "translation")
        )
    }

    @Test
    fun `a stored key reads back to itself`() {
        listOf(ReviewKey("translation", "menu.home"), ReviewKey("courier::messages", "welcome"), ReviewKey("ns", "a:b"))
            .forEach { assertEquals(it, ReviewKey.parse(it.stored)) }
        assertNull(ReviewKey.parse("no-namespace"))
    }

    @Test
    fun `the spelling leaves a default namespace out`() {
        assertEquals("menu.home", ReviewKey("translation", "menu.home").spelling(listOf("translation")))
        assertEquals("common:cart.title", key.spelling(listOf("translation")))
    }

    // --- edits ---

    @Test
    fun `marking to review records the value, the origin and a thread entry`() {
        val marked = ReviewEdits.markToReview(emptyMap(), mapOf(key to value), Review.machineOrigin("DeepL"), "Bob", "T1")

        val review = marked.getValue(key)
        assertEquals(ReviewState.TO_REVIEW, review.state)
        assertEquals(ReviewHash.of(value), review.hash)
        assertEquals("machine:DeepL", review.origin)
        assertEquals(listOf(ReviewEvent("r1", ReviewEventKind.TO_REVIEW, "Bob", "T1", "")), review.thread)
    }

    @Test
    fun `a decision keeps the origin and extends the thread`() {
        val marked = ReviewEdits.markToReview(emptyMap(), mapOf(key to "Chariot"), "machine:DeepL", "Bob", "T1")
        val requested = ReviewEdits.decide(marked, key, ReviewState.CHANGES_REQUESTED, "Chariot", "Alice", "T2", "Panier")
        val approved = ReviewEdits.decide(requested, key, ReviewState.APPROVED, value, "Alice", "T3")

        val review = approved.getValue(key)
        assertEquals(ReviewState.APPROVED, review.state)
        assertEquals("machine:DeepL", review.origin)
        assertEquals("Alice", review.by)
        assertEquals("T3", review.at)
        assertEquals(listOf("r1", "r2", "r3"), review.thread.map { it.id })
        assertEquals(
            listOf(ReviewEventKind.TO_REVIEW, ReviewEventKind.REQUEST_CHANGE, ReviewEventKind.APPROVE),
            review.thread.map { it.kind }
        )
    }

    @Test
    fun `a change request without a comment is refused`() {
        assertThrows<IllegalArgumentException> {
            ReviewEdits.decide(emptyMap(), key, ReviewState.CHANGES_REQUESTED, value, "Alice", "T1", " ")
        }
    }

    @Test
    fun `a comment leaves the decision as it is`() {
        val approved = ReviewEdits.decide(emptyMap(), key, ReviewState.APPROVED, value, "Alice", "T1")
        val commented = ReviewEdits.comment(approved, key, value, "Bob", "T2", "Nice")

        val review = commented.getValue(key)
        assertEquals(ReviewState.APPROVED, review.state)
        assertEquals("Alice", review.by)
        assertEquals(ReviewEvent("r2", ReviewEventKind.COMMENT, "Bob", "T2", "Nice"), review.thread.last())
    }

    @Test
    fun `a comment on a key never reviewed marks it to review`() {
        val review = ReviewEdits.comment(emptyMap(), key, value, "Bob", "T1", "Is this right?").getValue(key)
        assertEquals(ReviewState.TO_REVIEW, review.state)
        assertEquals(ReviewHash.of(value), review.hash)
    }
}
