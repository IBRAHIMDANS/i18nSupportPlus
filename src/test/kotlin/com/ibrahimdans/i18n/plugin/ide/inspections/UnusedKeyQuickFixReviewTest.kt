package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.UnusedTranslationKeyInspection
import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The *Delete key with no reference found* quick fix deletes the key's review with it, in the same
 * command, and leaves a project without `.i18n-reviews/` as it is.
 */
class UnusedKeyQuickFixReviewTest : PlatformBaseTest() {

    private fun store(): ReviewStore = ReviewStore.getInstance(project).apply { reviewer = { "Alice <alice@example.com>" } }

    private fun root(): VirtualFile = ReviewLocation.rootOf(project, null)!!

    private fun reviews(locale: String): Map<ReviewKey, Review> = store().reviews(root(), locale)

    /** Reviews on for the project, [approved] (key → value) approved in [locale]. */
    private fun approve(locale: String, approved: Map<ReviewKey, String>) {
        WriteCommandAction.runWriteCommandAction(project) {
            store().enable(root())
            approved.forEach { (key, value) -> store().decide(root(), locale, key, ReviewState.APPROVED, value) }
        }
    }

    /** Opens [path] with the caret on the key [key] and applies the quick fix there. */
    private fun deleteUnusedKey(path: String, key: String) {
        myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("\"$key\"") + 1)
        val fix = myFixture.getAvailableIntention(PluginBundle.getMessage("inspection.unused.fix.name"))
        Assertions.assertNotNull(fix, "quick fix should be offered on '$key'")
        myFixture.launchAction(fix!!)
        // A key with a review is deleted in a command of its own, run once the platform's ends.
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    @Test
    fun theQuickFixDeletesTheReviewOfTheKeyInItsLocale() {
        addFileToProject("locales/en/common.json", """{"a": "1", "b": "2"}""")
        addFileToProject("locales/fr/common.json", """{"a": "un", "b": "deux"}""")
        approve("en", mapOf(ReviewKey("common", "a") to "1", ReviewKey("common", "b") to "2"))
        approve("fr", mapOf(ReviewKey("common", "a") to "un"))

        deleteUnusedKey("locales/en/common.json", "a")

        Assertions.assertEquals("""{"b":"2"}""", myFixture.file.text.filterNot { it.isWhitespace() })
        Assertions.assertEquals(setOf(ReviewKey("common", "b")), reviews("en").keys)
        Assertions.assertEquals(setOf(ReviewKey("common", "a")), reviews("fr").keys, "the other locale keeps its value, and its review")
    }

    @Test
    fun oneUndoRestoresTheKeyAndItsReview() {
        addFileToProject("locales/en/common.json", """{"a": "1", "b": "2"}""")
        approve("en", mapOf(ReviewKey("common", "a") to "1", ReviewKey("common", "b") to "2"))

        deleteUnusedKey("locales/en/common.json", "a")
        // Ctrl+Z in the translation file's editor: the default dialog throws if a confirmation is asked.
        UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        Assertions.assertEquals("""{"a":"1","b":"2"}""", myFixture.file.text.filterNot { it.isWhitespace() })
        Assertions.assertEquals(setOf(ReviewKey("common", "a"), ReviewKey("common", "b")), reviews("en").keys)
    }

    @Test
    fun thePreviewLeavesTheReviewsAlone() {
        addFileToProject("locales/en/common.json", """{"a": "1", "b": "2"}""")
        approve("en", mapOf(ReviewKey("common", "a") to "1"))
        myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("locales/en/common.json"))
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("\"a\"") + 1)
        val fix = myFixture.getAvailableIntention(PluginBundle.getMessage("inspection.unused.fix.name"))!!

        val preview = myFixture.getIntentionPreviewText(fix)

        Assertions.assertEquals("""{"b":"2"}""", preview?.filterNot { it.isWhitespace() })
        Assertions.assertEquals(setOf(ReviewKey("common", "a")), reviews("en").keys)
    }

    @Test
    fun aProjectWithoutReviewsFolderIsLeftAsItIs() {
        addFileToProject("locales/en/common.json", """{"a": "1", "b": "2"}""")

        deleteUnusedKey("locales/en/common.json", "a")

        Assertions.assertEquals("""{"b":"2"}""", myFixture.file.text.filterNot { it.isWhitespace() })
        Assertions.assertNull(root().findChild(ReviewLocation.DIRECTORY), "no reviews folder may be created")
    }
}
