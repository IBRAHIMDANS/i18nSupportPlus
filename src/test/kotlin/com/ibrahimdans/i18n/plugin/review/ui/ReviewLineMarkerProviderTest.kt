package com.ibrahimdans.i18n.plugin.review.ui

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.lineMarkersOf
import com.ibrahimdans.i18n.plugin.ide.toolwindow.ReviewBadge
import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewHash
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The review markers of a translation file: one per reviewed value, the table's icon for its state,
 * and the gutter's *Approve* writing the review the table's writes.
 */
class ReviewLineMarkerProviderTest : PlatformBaseTest() {

    private fun store(): ReviewStore = ReviewStore.getInstance(project).apply {
        now = { Instant.parse("2026-11-01T22:30:00Z") }
        reviewer = { "Alice <alice@example.com>" }
    }

    private fun root(): VirtualFile = ReviewLocation.rootOf(project, null)!!

    /** Reviews on for the project, then [decisions] written. */
    private fun reviews(vararg decisions: (ReviewStore, VirtualFile) -> Unit) {
        WriteCommandAction.runWriteCommandAction(project) {
            store().enable(root())
            decisions.forEach { it(store(), root()) }
        }
    }

    private fun decide(locale: String, path: String, state: ReviewState, value: String, source: String? = null, text: String = "") =
        { store: ReviewStore, root: VirtualFile ->
            store.decide(root, locale, ReviewKey("common", path), state, value, text, source?.let { ReviewSource.of("en", it) })
            Unit
        }

    /** The review markers of [path], keyed by the text of the leaf each one sits on. */
    private fun markers(path: String): Map<String, ReviewLineMarkerInfo> {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
        myFixture.doHighlighting()
        return lineMarkersOf(myFixture.editor.document, project)
            .filterIsInstance<ReviewLineMarkerInfo>()
            .associateBy { it.element!!.text }
    }

    @Test
    fun eachReviewedValueShowsTheIconOfItsState() {
        addFileToProject("locales/en/common.json", """{"a": "A", "b": "B", "c": "C", "d": "D now", "e": "E", "menu": {"home": "Home"}}""")
        addFileToProject("locales/fr/common.json", """{"a": "Aa", "b": "Bb", "c": "Cc", "d": "Dd", "e": "Ee", "menu": {"home": "Accueil"}}""")
        reviews(
            decide("fr", "a", ReviewState.TO_REVIEW, "Aa"),
            decide("fr", "b", ReviewState.APPROVED, "Bb", source = "B"),
            decide("fr", "c", ReviewState.CHANGES_REQUESTED, "Cc", text = "Too literal"),
            decide("fr", "d", ReviewState.APPROVED, "Dd", source = "D before"),
            decide("fr", "menu.home", ReviewState.APPROVED, "Accueil, old"),
        )

        val markers = markers("locales/fr/common.json")

        val expected = mapOf(
            "\"a\"" to ShownReview.TO_REVIEW,
            "\"b\"" to ShownReview.APPROVED,
            "\"c\"" to ShownReview.CHANGES_REQUESTED,
            "\"d\"" to ShownReview.SOURCE_CHANGED,
            "\"home\"" to ShownReview.CHANGED_SINCE_APPROVAL,
        )
        Assertions.assertEquals(expected, markers.mapValues { it.value.state }, "a value never reviewed (e) shows nothing")
        markers.values.forEach { Assertions.assertSame(ReviewBadge.icon(it.state), it.icon) }
        Assertions.assertEquals(ReviewCell("common:menu.home", "fr", "Accueil", "Home"), markers.getValue("\"home\"").cell)
    }

    @Test
    fun approvingFromTheGutterWritesTheReviewTheTableWrites() {
        addFileToProject("locales/en/common.json", """{"save": "Save", "cancel": "Cancel"}""")
        addFileToProject("locales/fr/common.json", """{"save": "Enregistrer", "cancel": "Annuler"}""")
        reviews(decide("fr", "save", ReviewState.TO_REVIEW, "Enregistrer"), decide("fr", "cancel", ReviewState.TO_REVIEW, "Annuler"))

        val marker = markers("locales/fr/common.json").getValue("\"save\"")
        // The cell the table builds for the same value: its key spelled with the namespace, its source in `en`.
        Assertions.assertEquals(ReviewCell("common:save", "fr", "Enregistrer", "Save"), marker.cell)
        Assertions.assertEquals("en", marker.referenceLocale)

        Assertions.assertTrue(ReviewGutterActions.approve(project, marker.root, marker.cell, marker.referenceLocale))
        Assertions.assertTrue(ReviewActions.approve(project, root(), listOf(ReviewCell("common:cancel", "fr", "Annuler", "Cancel")), referenceLocale = "en"))

        val fr = store().reviews(root(), "fr")
        val fromGutter = fr.getValue(ReviewKey("common", "save"))
        val fromTable = fr.getValue(ReviewKey("common", "cancel"))
        Assertions.assertEquals(ReviewState.APPROVED, fromGutter.state)
        Assertions.assertEquals(ReviewHash.of("Enregistrer"), fromGutter.hash)
        Assertions.assertEquals(ReviewSource.of("en", "Save"), fromGutter.source)
        Assertions.assertEquals(decision(fromTable), decision(fromGutter))

        Assertions.assertEquals(ShownReview.APPROVED, markers("locales/fr/common.json").getValue("\"save\"").state)
    }

    /** What a decision records, the value and the source left aside: they differ between the two keys. */
    private fun decision(review: Review) =
        listOf(review.state, review.by, review.at, review.origin, review.source?.locale, review.thread.map { listOf(it.kind, it.by, it.at, it.text) })

    @Test
    fun nothingIsShownWhereTheModuleKeepsNoReviews() {
        addFileToProject("locales/en/common.json", """{"save": "Save"}""")
        addFileToProject("locales/fr/common.json", """{"save": "Enregistrer"}""")

        Assertions.assertEquals(emptyMap<String, ReviewLineMarkerInfo>(), markers("locales/fr/common.json"))
        Assertions.assertNull(ReviewLocation.directoryOf(root()), "the gutter must not create the reviews folder")
    }
}
