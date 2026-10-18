package com.ibrahimdans.i18n.plugin.review.ui

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.review.ReviewEventKind
import com.ibrahimdans.i18n.plugin.review.ReviewHash
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.review.shown
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
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
    fun aDecisionRecordsTheSourceOfTheCellInTheReferenceLocale() {
        val root = enabledRoot()
        val withSource = listOf(
            ReviewCell("menu.home", "fr", "Accueil", "Home"),
            ReviewCell("menu.home", "en", "Home", "Home"),
            ReviewCell("common:cart.title", "fr", "Panier", ""),
        )

        Assertions.assertTrue(ReviewActions.approve(project, root, withSource, ns, referenceLocale = "en"))

        val fr = store().reviews(root, "fr")
        Assertions.assertEquals(ReviewSource.of("en", "Home"), fr.getValue(ReviewKey(ns, "menu.home")).source)
        Assertions.assertNull(fr.getValue(ReviewKey("common", "cart.title")).source, "no source in the reference: none recorded")
        Assertions.assertNull(store().reviews(root, "en").getValue(ReviewKey(ns, "menu.home")).source, "the reference has no source of its own")
    }

    @Test
    fun withoutReferenceLocaleNoSourceIsRecordedAndTheKeyKeepsItsOwn() {
        val root = enabledRoot()
        val cell = ReviewCell("menu.home", "fr", "Accueil", "Home")
        Assertions.assertTrue(ReviewActions.approve(project, root, listOf(cell), ns))
        Assertions.assertNull(store().reviews(root, "fr").getValue(ReviewKey(ns, "menu.home")).source)

        Assertions.assertTrue(ReviewActions.requestChanges(project, root, listOf(cell), "Say Home page", ns, "en"))
        Assertions.assertTrue(ReviewActions.markToReview(project, root, listOf(cell.copy(source = "Home page")), ns))
        Assertions.assertTrue(ReviewActions.comment(project, root, cell.copy(source = "Home page"), "Fine", ns, "en"))

        // The change request recorded the source; neither the mark without reference nor the comment replaced it.
        Assertions.assertEquals(ReviewSource.of("en", "Home"), store().reviews(root, "fr").getValue(ReviewKey(ns, "menu.home")).source)
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

    /** The value at [key] in the JSON file [path] of the test project, its documents committed first. */
    private fun valueAt(path: String, vararg key: String): String? {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return ReadAction.compute<String?, RuntimeException> {
            val vf = myFixture.findFileInTempDir(path) ?: return@compute null
            val file = PsiManager.getInstance(project).findFile(vf) as? JsonFile ?: return@compute null
            var node = file.topLevelValue as? JsonObject ?: return@compute null
            for (i in 0 until key.size - 1) node = node.findProperty(key[i])?.value as? JsonObject ?: return@compute null
            (node.findProperty(key.last())?.value as? JsonStringLiteral)?.value
        }
    }

    @Test
    fun applyingASuggestionWritesTheValueMarksTheKeyToReviewAndOneUndoTakesBothBack() {
        addFileToProject("locales/fr/translation.json", """{"menu":{"home":"Acceuil"}}""")
        val root = enabledRoot()
        val cell = ReviewCell("menu.home", "fr", "Acceuil")
        Assertions.assertTrue(ReviewActions.requestChanges(project, root, listOf(cell), "Typo", ns, suggestion = "Accueil"))
        val requested = store().reviews(root, "fr")
        Assertions.assertEquals("Accueil", requested.getValue(ReviewKey(ns, "menu.home")).thread.last().suggestion)

        Assertions.assertTrue(ReviewActions.applySuggestion(project, root, cell, "Accueil", ns))

        Assertions.assertEquals("Accueil", valueAt("locales/fr/translation.json", "menu", "home"))
        val review = store().reviews(root, "fr").getValue(ReviewKey(ns, "menu.home"))
        Assertions.assertEquals(ReviewState.TO_REVIEW, review.state)
        Assertions.assertEquals(ReviewHash.of("Accueil"), review.hash)
        Assertions.assertEquals(ShownReview.TO_REVIEW, shown(review, "Accueil"))

        // One Ctrl+Z takes back the value and the mark together.
        UndoManager.getInstance(project).undo(null)

        Assertions.assertEquals("Acceuil", valueAt("locales/fr/translation.json", "menu", "home"))
        Assertions.assertEquals(requested, store().reviews(root, "fr"))
    }

    @Test
    fun aSuggestionTheCellHoldsAlreadyOrWithoutTranslationFileWritesNothing() {
        addFileToProject("locales/fr/translation.json", """{"menu":{"home":"Accueil"}}""")
        val root = enabledRoot()

        Assertions.assertFalse(ReviewActions.applySuggestion(project, root, ReviewCell("menu.home", "fr", "Accueil"), "Accueil", ns))
        Assertions.assertFalse(ReviewActions.applySuggestion(project, root, ReviewCell("menu.home", "de", "Start"), "Startseite", ns))

        Assertions.assertTrue(store().reviews(root, "fr").isEmpty())
        Assertions.assertTrue(store().reviews(root, "de").isEmpty())
    }
}
