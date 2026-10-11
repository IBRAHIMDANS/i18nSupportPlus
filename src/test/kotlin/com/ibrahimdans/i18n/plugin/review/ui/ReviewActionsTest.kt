package com.ibrahimdans.i18n.plugin.review.ui

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.review.ReviewEventKind
import com.ibrahimdans.i18n.plugin.review.ReviewHash
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant

/** The decisions of the table and the review dialog, on review files of the test project. */
class ReviewActionsTest : PlatformBaseTest() {

    private val ns = "translation"

    private fun store(): ReviewStore = ReviewStore.getInstance(project).apply {
        now = { Instant.parse("2026-10-11T14:02:11.123Z") }
        reviewer = { "Alice <alice@example.com>" }
    }

    private fun enabledRoot(): VirtualFile {
        val root = myFixture.tempDirFixture.findOrCreateDir("app")
        WriteCommandAction.runWriteCommandAction(project) { store().enable(root) }
        return root
    }

    private val cells = listOf(
        ReviewCell("menu.home", "fr", "Accueil"),
        ReviewCell("common:cart.title", "fr", "Panier"),
        ReviewCell("menu.home", "de", "Start"),
    )

    @Test
    fun approvingThreeCellsWritesThreeReviewsInOneCommand() {
        val root = enabledRoot()
        val store = store()

        Assertions.assertTrue(ReviewActions.approve(project, root, cells, ns))

        val fr = store.reviews(root, "fr")
        val de = store.reviews(root, "de")
        Assertions.assertEquals(setOf(ReviewKey(ns, "menu.home"), ReviewKey("common", "cart.title")), fr.keys)
        Assertions.assertEquals(setOf(ReviewKey(ns, "menu.home")), de.keys)
        (fr.values + de.values).forEach {
            Assertions.assertEquals(ReviewState.APPROVED, it.state)
            Assertions.assertEquals("Alice <alice@example.com>", it.by)
            Assertions.assertEquals("2026-10-11T14:02:11Z", it.at)
        }
        Assertions.assertEquals(ReviewHash.of("Panier"), fr.getValue(ReviewKey("common", "cart.title")).hash)

        // One Ctrl+Z, from the tool window — no editor holds the files — takes back all three.
        val undo = UndoManager.getInstance(project)
        Assertions.assertTrue(undo.isUndoAvailable(null), "the decision must be undoable without an editor")
        undo.undo(null)

        Assertions.assertEquals(emptyMap<ReviewKey, Any>(), store.reviews(root, "fr"))
        Assertions.assertEquals(emptyMap<ReviewKey, Any>(), store.reviews(root, "de"))
    }

    @Test
    fun aChangeRequestWithoutCommentIsRefusedAndWritesNothing() {
        val root = enabledRoot()

        Assertions.assertFalse(ReviewActions.requestChanges(project, root, cells, "", ns))
        Assertions.assertFalse(ReviewActions.requestChanges(project, root, cells, "  \n ", ns))

        Assertions.assertNull(ReviewLocation.directoryOf(root)!!.findChild(ReviewLocation.fileName("fr")))
        Assertions.assertTrue(store().reviews(root, "fr").isEmpty())
    }

    @Test
    fun aChangeRequestCarriesItsCommentToEveryCell() {
        val root = enabledRoot()

        Assertions.assertTrue(ReviewActions.requestChanges(project, root, cells.take(2), " Use the cart wording ", ns))

        store().reviews(root, "fr").values.forEach { review ->
            Assertions.assertEquals(ReviewState.CHANGES_REQUESTED, review.state)
            Assertions.assertEquals(ReviewEventKind.REQUEST_CHANGE, review.thread.last().kind)
            Assertions.assertEquals("Use the cart wording", review.thread.last().text)
        }
    }

    @Test
    fun markingToReviewKeepsTheOriginAndACommentKeepsTheDecision() {
        val root = enabledRoot()
        val key = ReviewKey(ns, "menu.home")
        WriteCommandAction.runWriteCommandAction(project) {
            store().mark(root, "fr", mapOf(key to "Accueil"), "machine:DeepL")
        }
        Assertions.assertTrue(ReviewActions.approve(project, root, cells.take(1), ns))

        Assertions.assertTrue(ReviewActions.markToReview(project, root, cells.take(1), ns))
        val marked = store().reviews(root, "fr").getValue(key)
        Assertions.assertEquals(ReviewState.TO_REVIEW, marked.state)
        Assertions.assertEquals("machine:DeepL", marked.origin)

        Assertions.assertTrue(ReviewActions.comment(project, root, cells[0], "Looks fine", ns))
        val commented = store().reviews(root, "fr").getValue(key)
        Assertions.assertEquals(ReviewState.TO_REVIEW, commented.state)
        Assertions.assertEquals("Looks fine", commented.thread.last().text)
        Assertions.assertEquals(4, commented.thread.size)
    }

    @Test
    fun aCellWithoutValueAndAModuleWithoutReviewsWriteNothing() {
        val root = enabledRoot()
        Assertions.assertFalse(ReviewActions.approve(project, root, listOf(ReviewCell("menu.home", "fr", "")), ns))
        Assertions.assertFalse(ReviewActions.comment(project, root, ReviewCell("menu.home", "fr", ""), "text", ns))

        val off = myFixture.tempDirFixture.findOrCreateDir("other")
        Assertions.assertFalse(ReviewActions.approve(project, off, cells, ns))
        Assertions.assertNull(ReviewLocation.directoryOf(off), "no folder may be created by a decision")
    }
}
