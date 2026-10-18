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

    // --- shown with the source: the truth table of the stale translations framing (§4.1) ---

    private val source = "Cart"
    private val sourceEn = ReviewSource.of("en", source)

    private fun decided(state: ReviewState?): Review? = state?.let { review(it).copy(source = sourceEn) }

    @Test
    fun `the truth table, twelve cases`() {
        val sameSource = ReviewSource.of("en", source)
        val otherSource = ReviewSource.of("en", "Basket")
        // Stored state → (same value and same source, same value and unknown source, same value and other source, other value).
        val table = mapOf(
            ReviewState.APPROVED to listOf(ShownReview.APPROVED, ShownReview.APPROVED, ShownReview.SOURCE_CHANGED, ShownReview.CHANGED_SINCE_APPROVAL),
            ReviewState.CHANGES_REQUESTED to listOf(ShownReview.CHANGES_REQUESTED, ShownReview.CHANGES_REQUESTED, ShownReview.SOURCE_CHANGED, ShownReview.FIXED_SINCE_REQUEST),
            ReviewState.TO_REVIEW to listOf(ShownReview.TO_REVIEW, ShownReview.TO_REVIEW, ShownReview.SOURCE_CHANGED, ShownReview.TO_REVIEW),
            null to listOf(ShownReview.NONE, ShownReview.NONE, ShownReview.NONE, ShownReview.NONE),
        )

        table.forEach { (state, expected) ->
            val r = decided(state)
            val actual = listOf(shown(r, value, sameSource), shown(r, value, null), shown(r, value, otherSource), shown(r, "Chariot", otherSource))
            assertEquals(expected, actual, "stored $state")
        }
    }

    @Test
    fun `a source edited after the approval makes it stale, a value edited next makes it to review`() {
        val approved = ReviewEdits.decide(emptyMap(), key, ReviewState.APPROVED, value, "Alice", "T1", source = sourceEn).getValue(key)
        val edited = ReviewSource.of("en", "Shopping cart")

        assertEquals(ShownReview.SOURCE_CHANGED, shown(approved, value, edited))
        assertEquals(ShownReview.CHANGED_SINCE_APPROVAL, shown(approved, "Panier d'achat", edited))
    }

    @Test
    fun `a source back to its former text is no longer stale`() {
        val approved = decided(ReviewState.APPROVED)

        assertEquals(ShownReview.SOURCE_CHANGED, shown(approved, value, ReviewSource.of("en", "Basket")))
        assertEquals(ShownReview.APPROVED, shown(approved, value, ReviewSource.of("en", source)))
    }

    @Test
    fun `a review without source is never stale`() {
        ReviewState.entries.forEach { state ->
            assertEquals(shown(review(state), value), shown(review(state), value, ReviewSource.of("en", "Basket")), "stored $state")
        }
    }

    @Test
    fun `a source taken in another locale than the reference of the day is not stale`() {
        assertEquals(ShownReview.APPROVED, shown(decided(ReviewState.APPROVED), value, ReviewSource.of("de", "Warenkorb")))
    }

    @Test
    fun `a key without source now is not stale`() {
        assertEquals(ShownReview.APPROVED, shown(decided(ReviewState.APPROVED), value, null))
    }

    @Test
    fun `a source read with CRLF or decomposed accents is the same source`() {
        val approved = review(ReviewState.APPROVED).copy(source = ReviewSource.of("en", "Line one\nCafé"))

        assertEquals(ShownReview.APPROVED, shown(approved, value, ReviewSource.of("en", "Line one\r\nCafé")))
        assertEquals(ShownReview.APPROVED, shown(approved, value, ReviewSource.of("en", "Line one\nCafé")))
    }

    @Test
    fun `a source hashes as a value does`() {
        assertEquals(ReviewSource("en", ReviewHash.of(source)), ReviewSource.of("en", source))
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
    fun `a change request records the value it suggests on its thread entry`() {
        val requested = ReviewEdits.decide(emptyMap(), key, ReviewState.CHANGES_REQUESTED, "Chariot", "Alice", "T1", "Say Panier", suggestion = value)

        val review = requested.getValue(key)
        assertEquals(ReviewEvent("r1", ReviewEventKind.REQUEST_CHANGE, "Alice", "T1", "Say Panier", value), review.thread.last())
        assertEquals(ShownReview.CHANGES_REQUESTED, shown(review, "Chariot"))
        // Once the suggestion is the value, the request reads as fixed: shown() knows nothing of suggestions.
        assertEquals(ShownReview.FIXED_SINCE_REQUEST, shown(review, value))
    }

    @Test
    fun `a blank suggestion, or one on another decision, is not recorded`() {
        val blank = ReviewEdits.decide(emptyMap(), key, ReviewState.CHANGES_REQUESTED, "Chariot", "Alice", "T1", "Say Panier", suggestion = " ")
        val approved = ReviewEdits.decide(emptyMap(), key, ReviewState.APPROVED, value, "Alice", "T1", suggestion = "Caddie")

        assertNull(blank.getValue(key).thread.last().suggestion)
        assertNull(approved.getValue(key).thread.last().suggestion)
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

    // --- edits and the source ---

    @Test
    fun `a decision records its source, and approving a stale value again records the new one`() {
        val approved = ReviewEdits.decide(emptyMap(), key, ReviewState.APPROVED, value, "Alice", "T1", source = sourceEn)
        assertEquals(sourceEn, approved.getValue(key).source)

        val edited = ReviewSource.of("en", "Shopping cart")
        val again = ReviewEdits.decide(approved, key, ReviewState.APPROVED, value, "Alice", "T2", source = edited).getValue(key)

        assertEquals(edited, again.source)
        assertEquals(ShownReview.APPROVED, shown(again, value, edited))
    }

    @Test
    fun `an edit without source keeps the one the key had`() {
        val approved = ReviewEdits.decide(emptyMap(), key, ReviewState.APPROVED, value, "Alice", "T1", source = sourceEn)

        assertEquals(sourceEn, ReviewEdits.decide(approved, key, ReviewState.TO_REVIEW, value, "Bob", "T2").getValue(key).source)
        assertEquals(sourceEn, ReviewEdits.markToReview(approved, mapOf(key to "Chariot"), "machine:DeepL", "Bob", "T2").getValue(key).source)
        assertNull(ReviewEdits.decide(emptyMap(), key, ReviewState.APPROVED, value, "Alice", "T1").getValue(key).source)
    }

    @Test
    fun `marking to review records the source each value was translated from`() {
        val other = ReviewKey("common", "cart.empty")
        val marked = ReviewEdits.markToReview(
            emptyMap(), mapOf(key to value, other to "Vide"), "machine:DeepL", "Bob", "T1", mapOf(key to sourceEn)
        )

        assertEquals(sourceEn, marked.getValue(key).source)
        assertNull(marked.getValue(other).source, "a value without source records none")
    }

    @Test
    fun `a comment records the source of a key never reviewed only`() {
        val fresh = ReviewEdits.comment(emptyMap(), key, value, "Bob", "T1", "Is this right?", sourceEn).getValue(key)
        assertEquals(sourceEn, fresh.source)

        // The decision stays the one made on the former source: recording today's would hide the change.
        val approved = ReviewEdits.decide(emptyMap(), key, ReviewState.APPROVED, value, "Alice", "T1", source = sourceEn)
        val commented = ReviewEdits.comment(approved, key, value, "Bob", "T2", "Nice", ReviewSource.of("en", "Basket")).getValue(key)
        assertEquals(sourceEn, commented.source)
    }

    @Test
    fun `the properties of a later version are kept by every edit`() {
        val extra = mapOf("assignee" to "\"Carol\"")
        val reviews = mapOf(key to review(ReviewState.TO_REVIEW).copy(extra = extra))

        assertEquals(extra, ReviewEdits.decide(reviews, key, ReviewState.APPROVED, value, "Alice", "T2").getValue(key).extra)
        assertEquals(extra, ReviewEdits.markToReview(reviews, mapOf(key to value), "manual", "Alice", "T2").getValue(key).extra)
        assertEquals(extra, ReviewEdits.comment(reviews, key, value, "Alice", "T2", "Hm").getValue(key).extra)
    }
}
