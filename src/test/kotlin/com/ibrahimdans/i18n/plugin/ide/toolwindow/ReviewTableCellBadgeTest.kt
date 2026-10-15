package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewEvent
import com.ibrahimdans.i18n.plugin.review.ReviewEventKind
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.icons.AllIcons
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a reviewed cell says, without a Swing component — as the [ValueStatus] states are tested:
 * an icon and words per state, never a tint alone.
 */
class ReviewTableCellBadgeTest {

    private val review = Review(
        ReviewState.CHANGES_REQUESTED, "sha256:0", "Alice <alice@example.com>", "2026-10-11T14:02:11Z",
        Review.machineOrigin("DeepL"),
        listOf(
            ReviewEvent("r1", ReviewEventKind.TO_REVIEW, "Bob", "2026-10-10T08:00:00Z"),
            ReviewEvent("r2", ReviewEventKind.REQUEST_CHANGE, "Alice <alice@example.com>", "2026-10-11T14:02:11Z", "Say <b>Panier</b>"),
            ReviewEvent("r3", ReviewEventKind.TO_REVIEW, "Bob", "2026-10-11T15:00:00Z"),
        )
    )

    @Test
    fun `each shown state has its icon, the three to-review states share the eye`() {
        assertNull(ReviewBadge.icon(ShownReview.NONE))
        assertSame(AllIcons.Actions.Show, ReviewBadge.icon(ShownReview.TO_REVIEW))
        assertSame(AllIcons.Actions.Show, ReviewBadge.icon(ShownReview.CHANGED_SINCE_APPROVAL))
        assertSame(AllIcons.Actions.Show, ReviewBadge.icon(ShownReview.FIXED_SINCE_REQUEST))
        assertSame(AllIcons.General.InspectionsOK, ReviewBadge.icon(ShownReview.APPROVED))
        assertSame(AllIcons.General.BalloonError, ReviewBadge.icon(ShownReview.CHANGES_REQUESTED))
    }

    @Test
    fun `a stale translation has its own icon and words, apart from the eye`() {
        assertSame(AllIcons.General.Warning, ReviewBadge.icon(ShownReview.SOURCE_CHANGED))
        assertEquals(PluginBundle.message("review.state.source.changed"), ReviewBadge.label(ShownReview.SOURCE_CHANGED))
        assertEquals(ReviewBadge.label(ShownReview.SOURCE_CHANGED), ReviewBadge.lines(review, ShownReview.SOURCE_CHANGED).first())
    }

    @Test
    fun `each shown state has its own words, which tell apart what the shared icon does not`() {
        val labels = ShownReview.entries.filter { it != ShownReview.NONE }.map { ReviewBadge.label(it) }

        assertEquals(labels.size, labels.toSet().size, "two states read the same: $labels")
        assertTrue(labels.none { it.isBlank() || it.startsWith("!") }, "a label is missing from the bundle: $labels")
        assertEquals("", ReviewBadge.label(ShownReview.NONE))
        assertEquals(PluginBundle.message("review.state.changed.since.approval"), ReviewBadge.label(ShownReview.CHANGED_SINCE_APPROVAL))
    }

    @Test
    fun `the tooltip says the state, the author and date, the origin and the last comment`() {
        val tooltip = ReviewBadge.tooltip("Chariot", review, ShownReview.CHANGES_REQUESTED)

        assertTrue(tooltip.startsWith("<html>Chariot<br><b>"), tooltip)
        assertTrue(tooltip.contains(PluginBundle.message("review.state.changes.requested")), tooltip)
        assertTrue(tooltip.contains("Alice &lt;alice@example.com&gt;"), "the author, escaped: $tooltip")
        assertTrue(tooltip.contains(ReviewBadge.date("2026-10-11T14:02:11Z")), tooltip)
        assertTrue(tooltip.contains(PluginBundle.message("review.origin.machine", "DeepL")), tooltip)
        // r3 says nothing: the last comment is r2's, its markup shown as typed.
        assertTrue(tooltip.contains("Say &lt;b&gt;Panier&lt;/b&gt;"), tooltip)
        assertFalse(tooltip.contains("<b>Panier"), "a comment must not inject markup: $tooltip")
    }

    @Test
    fun `a review without comment has no comment line, and the origins read in words`() {
        val approved = review.copy(state = ReviewState.APPROVED, origin = Review.ORIGIN_MANUAL, thread = emptyList())

        assertEquals(3, ReviewBadge.lines(approved, ShownReview.APPROVED).size)
        assertEquals(PluginBundle.message("review.origin.manual"), ReviewBadge.origin(Review.ORIGIN_MANUAL))
        assertEquals(PluginBundle.message("review.origin.i18nize"), ReviewBadge.origin(Review.ORIGIN_I18NIZE))
        assertEquals("plugin:x", ReviewBadge.origin("plugin:x"), "an origin of a newer version reads as is")
    }

    @Test
    fun `a date that is not an instant reads as is`() {
        assertEquals("T", ReviewBadge.date("T"))
        assertTrue(ReviewBadge.date("2026-10-11T14:02:11Z").matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""")))
    }
}
