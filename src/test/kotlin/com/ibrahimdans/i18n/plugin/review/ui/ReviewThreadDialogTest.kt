package com.ibrahimdans.i18n.plugin.review.ui

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.toolwindow.ReviewBadge
import com.ibrahimdans.i18n.plugin.review.ReviewEvent
import com.ibrahimdans.i18n.plugin.review.ReviewEventKind
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant

/** [ReviewThreadDialog] built headless: what it shows, and what its buttons write. */
class ReviewThreadDialogTest : PlatformBaseTest() {

    private val ns = "translation"
    private val cell = ReviewCell("menu.home", "fr", "Accueil")

    private fun enabledRoot(): VirtualFile {
        val root = myFixture.tempDirFixture.findOrCreateDir("app")
        ReviewStore.getInstance(project).apply {
            now = { Instant.parse("2026-10-11T14:02:11Z") }
            reviewer = { "Alice" }
        }
        WriteCommandAction.runWriteCommandAction(project) { ReviewStore.getInstance(project).enable(root) }
        return root
    }

    private fun <T> withDialog(root: VirtualFile, cell: ReviewCell = this.cell, block: (ReviewThreadDialog) -> T): T {
        val dialog = ReviewThreadDialog(project, root, cell, ns)
        try {
            return block(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    @Test
    fun aChangeRequestWithoutCommentIsRefused() = withDialog(enabledRoot()) { dialog ->
        dialog.input.text = "   "

        Assertions.assertFalse(dialog.requestChanges())
        Assertions.assertNull(dialog.review(), "nothing may be written")
    }

    @Test
    fun aChangeRequestTakesTheTextOfTheField() = withDialog(enabledRoot()) { dialog ->
        dialog.input.text = "Say « Page d'accueil »"

        Assertions.assertTrue(dialog.requestChanges())
        Assertions.assertEquals(ReviewState.CHANGES_REQUESTED, dialog.review()?.state)
        Assertions.assertEquals(ShownReview.CHANGES_REQUESTED, dialog.shownReview())
    }

    @Test
    fun aCommentIsAddedToTheThreadShownAndEmptiesTheField() = withDialog(enabledRoot()) { dialog ->
        Assertions.assertTrue(dialog.threadText().contains(PluginBundle.message("review.dialog.thread.empty")))
        Assertions.assertEquals(PluginBundle.message("review.dialog.state.none"), dialog.stateText())

        dialog.input.text = "Is <this> right?"
        Assertions.assertTrue(dialog.postComment())

        Assertions.assertEquals("", dialog.input.text)
        Assertions.assertTrue(dialog.threadText().contains("Is &lt;this&gt; right?"), dialog.threadText())
        // A comment on a key never reviewed is a call to look at it: the key is to review now.
        Assertions.assertEquals(ReviewBadge.label(ShownReview.TO_REVIEW), dialog.stateText())
    }

    @Test
    fun theThreadSaysWhoDidWhatAndEscapesWhatWasSaid() {
        val html = ReviewThreadDialog.threadHtml(
            listOf(
                ReviewEvent("r1", ReviewEventKind.APPROVE, "Alice <a@x>", "2026-10-11T14:02:11Z"),
                ReviewEvent("r2", ReviewEventKind.COMMENT, "Bob", "2026-10-11T15:00:00Z", "<script>"),
            )
        )

        Assertions.assertTrue(html.contains("Alice &lt;a@x&gt; " + PluginBundle.message("review.event.approve")), html)
        Assertions.assertTrue(html.contains("&lt;script&gt;"), html)
        Assertions.assertFalse(html.contains("<script>"), html)
    }

    @Test
    fun aSuggestedValueIsShownWithALinkToApplyItWhileTheCellHoldsAnother() {
        val request = ReviewEvent("r1", ReviewEventKind.REQUEST_CHANGE, "Alice", "2026-10-11T14:02:11Z", "Typo", "<Accueil>")
        val link = ReviewThreadDialog.APPLY_LINK + "r1"

        val html = ReviewThreadDialog.threadHtml(listOf(request), "Acceuil")
        Assertions.assertTrue(html.contains(PluginBundle.message("review.dialog.suggested", "&lt;Accueil&gt;")), html)
        Assertions.assertTrue(html.contains(link), html)
        Assertions.assertFalse(ReviewThreadDialog.threadHtml(listOf(request), "<Accueil>").contains(link), "the value is the suggestion already")
        Assertions.assertFalse(ReviewThreadDialog.threadHtml(listOf(request)).contains(link), "nothing to write over without a value")
    }

    @Test
    fun aChangeRequestCarriesTheSuggestedValueAndApplyingItMarksTheKeyToReview() {
        addFileToProject("locales/fr/translation.json", """{"menu":{"home":"Acceuil"}}""")
        val root = enabledRoot()
        val typo = ReviewCell("menu.home", "fr", "Acceuil")
        withDialog(root, typo) { dialog ->
            dialog.input.text = "Typo"
            dialog.suggestionField.text = "Accueil"
            Assertions.assertTrue(dialog.requestChanges())
        }

        withDialog(root, typo) { dialog ->
            val id = dialog.review()!!.thread.last().id
            Assertions.assertTrue(dialog.applySuggestion(id))
            Assertions.assertEquals(ReviewState.TO_REVIEW, dialog.review()?.state)
            Assertions.assertFalse(dialog.applySuggestion("r404"), "no such entry")
        }
    }

    @Test
    fun openShowsNothingWhereReviewsAreOff() {
        // The test project's directory has no reviews folder.
        Assertions.assertFalse(ReviewThreadDialog.open(project, null, "menu.home", "fr", "Accueil"))
    }

    @Test
    fun theKeyOfANamedNamespaceIsReadUnderIt() {
        val root = enabledRoot()
        Assertions.assertTrue(ReviewActions.approve(project, root, listOf(ReviewCell("common:cart.title", "fr", "Panier")), ns))

        withDialog(root, ReviewCell("common:cart.title", "fr", "Panier")) { dialog ->
            Assertions.assertEquals(ShownReview.APPROVED, dialog.shownReview())
        }
        Assertions.assertNotNull(ReviewStore.getInstance(project).reviews(root, "fr")[ReviewKey("common", "cart.title")])
    }

    /** fr's menu.home approved on "Accueil" against the English source "Home". */
    private fun approvedOnHome(): VirtualFile {
        val root = enabledRoot()
        Assertions.assertTrue(ReviewActions.approve(project, root, listOf(cell.copy(source = "Home")), ns, referenceLocale = "en"))
        return root
    }

    private fun <T> withStaleDialog(root: VirtualFile, cell: ReviewCell, block: (ReviewThreadDialog) -> T): T {
        val dialog = ReviewThreadDialog(project, root, cell, ns, referenceLocale = "en")
        try {
            return block(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    @Test
    fun aSourceChangedSinceTheDecisionIsShownAsSuch() {
        val root = approvedOnHome()

        withStaleDialog(root, cell.copy(source = "Home page")) { dialog ->
            Assertions.assertEquals(ShownReview.SOURCE_CHANGED, dialog.shownReview())
            Assertions.assertEquals(ReviewBadge.label(ShownReview.SOURCE_CHANGED), dialog.stateText())
        }
        withStaleDialog(root, cell.copy(source = "Home")) { dialog ->
            Assertions.assertEquals(ShownReview.APPROVED, dialog.shownReview(), "the source the decision was made on")
        }
        // Without the reference locale, the source cannot be compared: the approval holds.
        withDialog(root, cell.copy(source = "Home page")) { dialog ->
            Assertions.assertEquals(ShownReview.APPROVED, dialog.shownReview())
        }
    }

    @Test
    fun approvingAgainRecordsTheSourceOfNow() {
        val root = approvedOnHome()

        withStaleDialog(root, cell.copy(source = "Home page")) { dialog ->
            Assertions.assertTrue(dialog.approve())
            Assertions.assertEquals(ShownReview.APPROVED, dialog.shownReview())
        }
        Assertions.assertEquals(ReviewSource.of("en", "Home page"), ReviewStore.getInstance(project).reviews(root, "fr")[ReviewKey(ns, "menu.home")]?.source)
    }

    @Test
    fun aChangeRequestRecordsTheSourceOfNowAndTheReferenceLocaleIsNeverStale() {
        val root = enabledRoot()

        withStaleDialog(root, cell.copy(source = "Home page")) { dialog ->
            dialog.input.text = "Say « Page d'accueil »"
            Assertions.assertTrue(dialog.requestChanges())
        }
        Assertions.assertEquals(ReviewSource.of("en", "Home page"), ReviewStore.getInstance(project).reviews(root, "fr")[ReviewKey(ns, "menu.home")]?.source)

        val english = ReviewCell("menu.home", "en", "Home", "Home page")
        Assertions.assertTrue(ReviewActions.approve(project, root, listOf(english), ns, referenceLocale = "en"))
        withStaleDialog(root, english) { dialog ->
            Assertions.assertEquals(ShownReview.APPROVED, dialog.shownReview())
        }
        Assertions.assertNull(ReviewStore.getInstance(project).reviews(root, "en")[ReviewKey(ns, "menu.home")]?.source)
    }
}
