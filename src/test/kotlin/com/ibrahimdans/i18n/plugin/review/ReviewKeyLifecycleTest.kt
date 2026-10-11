package com.ibrahimdans.i18n.plugin.review

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.CleanupUnusedKeysAction
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.actions.MoveI18nKeyHandler
import com.ibrahimdans.i18n.plugin.ide.actions.RenameI18nKeyHandler
import com.ibrahimdans.i18n.plugin.ide.toolwindow.OrphanKeyDeleter
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.review.ui.CleanupReviewsAction
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The reviews of a key follow it when it is renamed, moved to another namespace or deleted, in the
 * same command; and *Clean Up Orphan Reviews* only offers the reviews whose key is really gone.
 */
class ReviewKeyLifecycleTest : PlatformBaseTest() {

    @AfterEach
    fun resetDialogs() {
        TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
    }

    private fun store(): ReviewStore = ReviewStore.getInstance(project).apply { reviewer = { "Alice <alice@example.com>" } }

    private fun root(): VirtualFile = ReviewLocation.rootOf(project, null)!!

    /** Reviews on for the project, [approved] (key → value) approved in [locale]. */
    private fun approve(locale: String, approved: Map<ReviewKey, String>) {
        WriteCommandAction.runWriteCommandAction(project) {
            store().enable(root())
            approved.forEach { (key, value) -> store().decide(root(), locale, key, ReviewState.APPROVED, value) }
        }
    }

    private fun reviews(locale: String): Map<ReviewKey, Review> = store().reviews(root(), locale)

    private fun assertApproved(locale: String, key: ReviewKey, value: String) {
        val review = reviews(locale)[key]
        Assertions.assertNotNull(review, "no review under $key: ${reviews(locale).keys}")
        Assertions.assertEquals(ShownReview.APPROVED, shown(review, value), "the value did not change: the approval holds")
    }

    private fun renameAtCaret(newName: String) {
        TestDialogManager.setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = newName
            override fun show(message: String, validator: InputValidator?): String = newName
        })
        TestDialogManager.setTestDialog { Messages.YES }
        RenameI18nKeyHandler().invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)
    }

    private fun jsonLeaf(path: String, vararg key: String): JsonStringLiteral {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path)) as JsonFile
        var obj = file.topLevelValue as JsonObject
        for (i in 0 until key.size - 1) obj = obj.findProperty(key[i])!!.value as JsonObject
        return obj.findProperty(key.last())!!.value as JsonStringLiteral
    }

    @Test
    fun renamingALeafMovesItsReviewInEveryLocale() {
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save"}}""")
        addFileToProject("locales/fr/common.json", """{"button": {"save": "Enregistrer"}}""")
        approve("en", mapOf(ReviewKey("common", "button.save") to "Save"))
        approve("fr", mapOf(ReviewKey("common", "button.save") to "Enregistrer"))
        myFixture.configureByText("First.js", "export const a = (t) => t('common:button.sa<caret>ve');")

        renameAtCaret("store")

        Assertions.assertNull(reviews("fr")[ReviewKey("common", "button.save")])
        assertApproved("en", ReviewKey("common", "button.store"), "Save")
        assertApproved("fr", ReviewKey("common", "button.store"), "Enregistrer")
    }

    @Test
    fun renamingAPluralMovesTheReviewOfEachForm() {
        addFileToProject("locales/en/common.json", """{"cart": {"item_one": "{{count}} item", "item_other": "{{count}} items"}}""")
        approve("en", mapOf(ReviewKey("common", "cart.item_one") to "{{count}} item", ReviewKey("common", "cart.item_other") to "{{count}} items"))
        myFixture.configureByText("Cart.js", "export const a = (t, count) => t('common:cart.it<caret>em', { count });")

        renameAtCaret("product")

        Assertions.assertEquals(setOf(ReviewKey("common", "cart.product_one"), ReviewKey("common", "cart.product_other")), reviews("en").keys)
        assertApproved("en", ReviewKey("common", "cart.product_other"), "{{count}} items")
    }

    @Test
    fun renamingALevelMovesTheReviewsOfEveryKeyBelowIt() {
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save", "cancel": "Cancel"}, "buttons": {"title": "Buttons"}}""")
        approve(
            "en",
            mapOf(
                ReviewKey("common", "button.save") to "Save",
                ReviewKey("common", "button.cancel") to "Cancel",
                ReviewKey("common", "buttons.title") to "Buttons",
            )
        )
        myFixture.configureByText("First.js", "export const a = (t) => t('common:but<caret>ton.save');")

        renameAtCaret("actions")

        Assertions.assertEquals(
            setOf(ReviewKey("common", "actions.save"), ReviewKey("common", "actions.cancel"), ReviewKey("common", "buttons.title")),
            reviews("en").keys
        )
        assertApproved("en", ReviewKey("common", "actions.cancel"), "Cancel")
    }

    @Test
    fun movingAKeyToAnotherNamespaceMovesItsReviews() {
        addFileToProject("locales/en/common.json", """{"user": {"name": "Name", "age": "Age"}}""")
        addFileToProject("locales/fr/common.json", """{"user": {"name": "Nom"}}""")
        addFileToProject("locales/en/profile.json", "{}")
        approve("en", mapOf(ReviewKey("common", "user.name") to "Name", ReviewKey("common", "user.age") to "Age"))
        approve("fr", mapOf(ReviewKey("common", "user.name") to "Nom"))

        WriteCommandAction.runWriteCommandAction(project) {
            val leaves = listOf(jsonLeaf("locales/en/common.json", "user", "name"), jsonLeaf("locales/fr/common.json", "user", "name"))
            MoveI18nKeyHandler().execute(project, leaves, emptyList(), listOf(Literal("user"), Literal("name")), "profile")
        }

        Assertions.assertEquals(setOf(ReviewKey("profile", "user.name"), ReviewKey("common", "user.age")), reviews("en").keys)
        assertApproved("en", ReviewKey("profile", "user.name"), "Name")
        assertApproved("fr", ReviewKey("profile", "user.name"), "Nom")
    }

    @Test
    fun deletingAKeyDeletesItsReviewsAndOneUndoRestoresThem() {
        addFileToProject("locales/en/common.json", """{"a": "1", "b": "2"}""")
        addFileToProject("locales/fr/common.json", """{"a": "un", "b": "deux"}""")
        approve("en", mapOf(ReviewKey("common", "a") to "1", ReviewKey("common", "b") to "2"))
        approve("fr", mapOf(ReviewKey("common", "a") to "un"))

        PlatformTestUtil.waitForPromise(OrphanKeyDeleter(project).delete(KeysSynchronizer().buildFullKey("common:a")))

        Assertions.assertEquals(setOf(ReviewKey("common", "b")), reviews("en").keys)
        Assertions.assertEquals(emptySet<ReviewKey>(), reviews("fr").keys)

        UndoManager.getInstance(project).undo(null)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertApproved("en", ReviewKey("common", "a"), "1")
        assertApproved("fr", ReviewKey("common", "a"), "un")
    }

    @Test
    fun cleanupUnusedKeysDeletesTheirReviews() {
        addFileToProject("locales/en/common.json", """{"a": "1", "b": "2"}""")
        approve("en", mapOf(ReviewKey("common", "a") to "1", ReviewKey("common", "b") to "2"))

        CleanupUnusedKeysAction().deleteKeys(project, listOf("common:a"))

        Assertions.assertEquals(setOf(ReviewKey("common", "b")), reviews("en").keys)
    }

    @Test
    fun aProjectWithoutReviewsFolderIsLeftAsItIs() {
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save"}, "a": "1"}""")
        myFixture.configureByText("First.js", "export const a = (t) => t('common:button.sa<caret>ve');")

        renameAtCaret("store")
        PlatformTestUtil.waitForPromise(OrphanKeyDeleter(project).delete(KeysSynchronizer().buildFullKey("common:a")))

        Assertions.assertNull(root().findChild(ReviewLocation.DIRECTORY), "no reviews folder may be created")
    }

    @Test
    fun cleanupOffersOnlyTheReviewsWhoseKeyIsGoneThenDeletesThem() {
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save"}}""")
        approve("en", mapOf(ReviewKey("common", "button.save") to "Save", ReviewKey("common", "gone.key") to "Gone"))

        val scan = CleanupReviewsAction.scan(project)

        Assertions.assertEquals(listOf("en" to ReviewKey("common", "gone.key")), scan.found.flatMap { found -> found.orphans.map { it.locale to it.key } })
        Assertions.assertEquals(1, CleanupReviewsAction.delete(project, scan))
        Assertions.assertEquals(setOf(ReviewKey("common", "button.save")), reviews("en").keys)
    }

    @Test
    fun cleanupDeletesNothingWhenTheTranslationsLoadEmpty() {
        approve("en", mapOf(ReviewKey("common", "button.save") to "Save"))

        val scan = CleanupReviewsAction.scan(project)

        Assertions.assertEquals(0, scan.count)
        Assertions.assertEquals(1, scan.skipped.size, "the module is reported, not cleaned")
        Assertions.assertEquals(setOf(ReviewKey("common", "button.save")), reviews("en").keys)
    }
}
