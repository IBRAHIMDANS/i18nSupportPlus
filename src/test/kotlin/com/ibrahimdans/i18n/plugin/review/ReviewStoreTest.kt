package com.ibrahimdans.i18n.plugin.review

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * [ReviewStore] on the test project. The root is handed over as a directory of the fixture rather
 * than resolved from a module: project-relative paths are not where a light fixture keeps its files.
 */
class ReviewStoreTest : PlatformBaseTest() {

    private val key = ReviewKey("common", "cart.title")

    private fun store(): ReviewStore = ReviewStore.getInstance(project).apply {
        now = { Instant.parse("2026-10-11T14:02:11.123Z") }
        reviewer = { "Alice <alice@example.com>" }
    }

    private fun root(path: String): VirtualFile = myFixture.tempDirFixture.findOrCreateDir(path)

    private fun write(block: () -> Unit) = WriteCommandAction.runWriteCommandAction(project, block)

    private fun textOf(file: VirtualFile): String = FileDocumentManager.getInstance().getDocument(file)!!.text

    @Test
    fun withoutFolderReviewsAreOffAndNothingIsCreated() {
        val root = root("app")
        val store = store()

        Assertions.assertFalse(store.isEnabled(root))
        Assertions.assertEquals(emptyMap<ReviewKey, Review>(), store.reviews(root, "fr"))
        write { Assertions.assertFalse(store.mark(root, "fr", mapOf(key to "Panier"), "machine:DeepL")) }
        Assertions.assertNull(root.findChild(ReviewLocation.DIRECTORY), "no folder may be created without a request")
    }

    @Test
    fun aMarkWritesTheLocaleFileAndReadsBack() {
        val root = root("app")
        val store = store()

        write {
            store.enable(root)
            Assertions.assertTrue(store.mark(root, "fr", mapOf(key to "Panier"), Review.machineOrigin("DeepL")))
        }

        val review = store.reviews(root, "fr").getValue(key)
        Assertions.assertEquals(ReviewState.TO_REVIEW, review.state)
        Assertions.assertEquals(ReviewHash.of("Panier"), review.hash)
        Assertions.assertEquals("Alice <alice@example.com>", review.by)
        Assertions.assertEquals("2026-10-11T14:02:11Z", review.at)
        val file = root.findFileByRelativePath(".i18n-reviews/fr.json")!!
        Assertions.assertEquals(ReviewCodec.write(mapOf(key to review)), textOf(file))
        Assertions.assertEquals(emptyMap<ReviewKey, Review>(), store.reviews(root, "de"), "one file per locale")
    }

    @Test
    fun theSourceOfAMarkADecisionAndACommentIsWrittenAndReadBack() {
        val root = root("app")
        val store = store()
        val other = ReviewKey("common", "cart.empty")
        val save = ReviewSource.of("en", "Cart")
        val edited = ReviewSource.of("en", "Shopping cart")

        write {
            store.enable(root)
            store.mark(root, "fr", mapOf(key to "Panier"), Review.machineOrigin("DeepL"), mapOf(key to save))
            store.comment(root, "fr", other, "Vide", "Is this right?", save)
        }
        Assertions.assertEquals(save, store.reviews(root, "fr").getValue(key).source)
        Assertions.assertEquals(save, store.reviews(root, "fr").getValue(other).source)

        write { store.decide(root, "fr", key, ReviewState.APPROVED, "Panier") }
        Assertions.assertEquals(save, store.reviews(root, "fr").getValue(key).source, "no source given: the key keeps its own")

        write { store.decide(root, "fr", key, ReviewState.APPROVED, "Panier", source = edited) }
        val review = store.reviews(root, "fr").getValue(key)
        Assertions.assertEquals(edited, review.source)
        Assertions.assertEquals(ShownReview.APPROVED, shown(review, "Panier", edited))
        Assertions.assertTrue("\"sourceHash\": \"${edited.hash}\"" in textOf(root.findFileByRelativePath(".i18n-reviews/fr.json")!!))
    }

    @Test
    fun anEmptyOrUnreadableFileHoldsNoReviewAndIsNeverOverwritten() {
        val empty = addFileToProject("empty/.i18n-reviews/fr.json", "").virtualFile
        val broken = addFileToProject("broken/.i18n-reviews/fr.json", "<<<<<<< HEAD\n{").virtualFile
        val store = store()

        Assertions.assertEquals(emptyMap<ReviewKey, Review>(), store.reviews(empty.parent.parent, "fr"))
        Assertions.assertEquals(emptyMap<ReviewKey, Review>(), store.reviews(broken.parent.parent, "fr"))

        write {
            Assertions.assertTrue(store.mark(empty.parent.parent, "fr", mapOf(key to "Panier"), "manual"))
            Assertions.assertFalse(store.mark(broken.parent.parent, "fr", mapOf(key to "Panier"), "manual"))
        }
        Assertions.assertEquals(setOf(key), store.reviews(empty.parent.parent, "fr").keys)
        Assertions.assertEquals("<<<<<<< HEAD\n{", textOf(broken))
    }

    @Test
    fun aChangeOfTheFileIsReadAgain() {
        val file = addFileToProject("app/.i18n-reviews/fr.json", "").virtualFile
        val root = file.parent.parent
        val store = store()
        Assertions.assertEquals(emptyMap<ReviewKey, Review>(), store.reviews(root, "fr"))

        val outside = mapOf(key to Review(ReviewState.APPROVED, ReviewHash.of("Panier"), "Bob", "T"))
        write { FileDocumentManager.getInstance().getDocument(file)!!.setText(ReviewCodec.write(outside)) }

        Assertions.assertEquals(outside, store.reviews(root, "fr"))
    }

    @Test
    fun aChangeOnDiskIsReadAgainAndAnnounced() {
        val root = root("app")
        val store = store()
        lateinit var file: VirtualFile
        write { file = store.enable(root).createChildData(this, "fr.json") }
        Assertions.assertEquals(emptyMap<ReviewKey, Review>(), store.reviews(root, "fr"))
        var told = 0
        project.messageBus.connect(testRootDisposable).subscribe(ReviewStore.CHANGED, ReviewStore.Listener { told++ })

        // What a `git pull` does: the file changes on disk, no document involved.
        val pulled = mapOf(key to Review(ReviewState.APPROVED, ReviewHash.of("Panier"), "Bob", "T"))
        write { file.setBinaryContent(ReviewCodec.write(pulled).toByteArray()) }

        Assertions.assertTrue(told > 0, "a change on disk must be announced")
        Assertions.assertEquals(pulled, store.reviews(root, "fr"))
    }

    @Test
    fun everyWriteIsAnnounced() {
        val root = root("app")
        val store = store()
        val before = ReviewCodec.write(mapOf(key to Review(ReviewState.TO_REVIEW, ReviewHash.of("Chariot"), "Bob", "T")))
        write { store.enable(root).createChildData(this, "fr.json").setBinaryContent(before.toByteArray()) }
        var told = 0
        project.messageBus.connect(testRootDisposable).subscribe(ReviewStore.CHANGED, ReviewStore.Listener { told++ })

        write { store.decide(root, "fr", key, ReviewState.APPROVED, "Panier") }

        // The document is changed, not yet saved: no file event, the store's own announcement only.
        Assertions.assertEquals(1, told)
    }

    @Test
    fun aWriteInTheCallersCommandIsUndoneByOneUndo() {
        val before = ReviewCodec.write(mapOf(key to Review(ReviewState.TO_REVIEW, ReviewHash.of("Chariot"), "Bob", "T")))
        val file = addFileToProject("app/.i18n-reviews/fr.json", before).virtualFile
        val root = file.parent.parent
        val store = store()
        myFixture.configureFromExistingVirtualFile(file)

        WriteCommandAction.writeCommandAction(project).withName("Approve").run<RuntimeException> {
            store.decide(root, "fr", key, ReviewState.APPROVED, "Panier")
        }
        Assertions.assertEquals(ReviewState.APPROVED, store.reviews(root, "fr").getValue(key).state)

        UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor!!)

        Assertions.assertEquals(before, textOf(file))
        Assertions.assertEquals(ReviewState.TO_REVIEW, store.reviews(root, "fr").getValue(key).state)
    }

    @Test
    fun outsideAGitRepositoryTheOsAccountSigns() {
        Assertions.assertNotNull(
            project.getService(VcsReviewerIdentity::class.java),
            "gitConfig.xml must register the Git identity in the test sandbox"
        )
        Assertions.assertEquals("", ReviewerSettings.getInstance().name)

        Assertions.assertEquals(System.getProperty("user.name"), ReviewerIdentity.current(project, root("app")))
    }

    @Test
    fun aReviewFileIsNeverReadAsATranslation() {
        addFileToProject("locales/fr.json", """{"cart": {"title": "Panier"}}""")
        val reviews = ReviewCodec.write(mapOf(key to Review(ReviewState.APPROVED, ReviewHash.of("Panier"), "Bob", "T")))
        addFileToProject(".i18n-reviews/fr.json", reviews)
        addFileToProject("apps/web/.i18n-reviews/en.json", reviews)

        val paths = ReadAction.compute<List<String>, RuntimeException> {
            project.service<LocalizationSourceService>().findAllSources(project).map { it.displayPath }
        }

        Assertions.assertTrue(paths.any { it.endsWith("locales/fr.json") }, "the translation must be found, got: $paths")
        Assertions.assertFalse(paths.any { ReviewLocation.DIRECTORY in it }, "a review file was read as a translation: $paths")
        Assertions.assertEquals(listOf("fr"), TranslationDataLoader.discoverLocales(project))
    }
}
