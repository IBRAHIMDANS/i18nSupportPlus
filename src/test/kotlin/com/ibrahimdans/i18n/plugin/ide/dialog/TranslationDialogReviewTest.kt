package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.toolwindow.ReviewBadge
import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewHash
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.review.ui.ReviewThreadDialog
import com.ibrahimdans.i18n.plugin.translate.TranslatedBy
import com.ibrahimdans.i18n.plugin.translate.Translation
import com.ibrahimdans.i18n.plugin.translate.TranslationProvider
import com.ibrahimdans.i18n.plugin.translate.TranslationRequest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The translation dialog and the review of translations: a locale *Translate* filled and validated
 * as proposed is marked to review in the command of OK, with the source it was translated from, one
 * retouched is not, a locale whose source changed since its review says so, and a module keeping no
 * reviews sees no mark, no link and no folder.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class TranslationDialogReviewTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")
    private val originalTranslator = TranslationDialog.machineTranslator
    private val originalRoot = TranslationDialog.reviewRoot
    private val originalThread = TranslationDialog.reviewThread
    private val key = ReviewKey("common", "greeting")

    @AfterEach
    fun restore() {
        TranslationDialog.machineTranslator = originalTranslator
        TranslationDialog.reviewRoot = originalRoot
        TranslationDialog.reviewThread = originalThread
    }

    /** The *Review…* link opens a thread that [act]s on it, as a click would, then closes. */
    private fun onReviewThread(act: (ReviewThreadDialog) -> Unit) {
        TranslationDialog.reviewThread = { thread ->
            try {
                act(thread)
            } finally {
                Disposer.dispose(thread.disposable)
            }
        }
    }

    /** `en` holds the key, `fr` lacks it; the engine answers `Bonjour`, from DeepL. */
    private fun seed(): VirtualFile {
        addFileToProject("locales/en/common.json", """{"greeting": "Hello"}""")
        val fr = addFileToProject("locales/fr/common.json", "{}")
        TranslationDialog.machineTranslator = {
            object : TranslationProvider {
                override fun translate(request: TranslationRequest, indicator: ProgressIndicator?): List<Translation> =
                    listOf(Translation.Done("Bonjour", TranslatedBy("DeepL", local = false)))
            }
        }
        return fr.virtualFile
    }

    /** A module root of the fixture, standing for the one the dialog resolves; reviews on when [enabled]. */
    private fun reviewRoot(enabled: Boolean): VirtualFile {
        val root = myFixture.tempDirFixture.findOrCreateDir("app")
        if (enabled) WriteAction.runAndWait<RuntimeException> { root.createChildDirectory(this, ReviewLocation.DIRECTORY) }
        TranslationDialog.reviewRoot = { _, _ -> root.takeIf { store().isEnabled(it) } }
        return root
    }

    private fun store(): ReviewStore = ReviewStore.getInstance(project).apply {
        now = { Instant.parse("2026-10-11T14:02:11Z") }
        reviewer = { "Alice <alice@example.com>" }
    }

    private fun withDialog(fullKey: String = "common:greeting", block: (TranslationDialog) -> Unit) {
        val dialog = TranslationDialog(project, KeysSynchronizer().buildFullKey(fullKey, config), Mode.EDIT)
        try {
            block(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    private fun frText(fr: VirtualFile): String =
        FileDocumentManager.getInstance().getDocument(fr)!!.text

    @Test
    fun `a locale translated then validated as proposed is marked to review, with its engine`() = myFixture.runWithConfig(config) {
        val fr = seed()
        val root = reviewRoot(enabled = true)

        withDialog { dialog ->
            dialog.translateEmptyLocales()
            dialog.confirm()
        }

        Assertions.assertTrue("Bonjour" in frText(fr), frText(fr))
        val review = store().reviews(root, "fr").getValue(key)
        Assertions.assertEquals(ReviewState.TO_REVIEW, review.state)
        Assertions.assertEquals(ReviewHash.of("Bonjour"), review.hash)
        Assertions.assertEquals("machine:DeepL", review.origin)
        Assertions.assertEquals(ReviewSource.of("en", "Hello"), review.source, "the text it was translated from")
        Assertions.assertEquals(emptyMap<ReviewKey, Review>(), store().reviews(root, "en"), "the donor locale is not marked")
    }

    /** `fr` approved on `Salut` when `en` read `Hi`; `en` reads `Hello` now: the approval no longer holds. */
    private fun seedStale(): Pair<VirtualFile, VirtualFile> {
        addFileToProject("locales/en/common.json", """{"greeting": "Hello"}""")
        val fr = addFileToProject("locales/fr/common.json", """{"greeting": "Salut"}""").virtualFile
        TranslationDialog.machineTranslator = { engine("Bonjour") }
        val root = reviewRoot(enabled = true)
        WriteAction.runAndWait<RuntimeException> {
            store().decide(root, "fr", key, ReviewState.APPROVED, "Salut", source = ReviewSource.of("en", "Hi"))
        }
        return fr to root
    }

    private fun engine(answer: String, requests: MutableList<TranslationRequest> = mutableListOf()) = object : TranslationProvider {
        override fun translate(request: TranslationRequest, indicator: ProgressIndicator?): List<Translation> {
            requests += request
            return listOf(Translation.Done(answer, TranslatedBy("DeepL", local = false)))
        }
    }

    private fun label(state: ShownReview): String = ReviewBadge.label(state)

    @Test
    fun `a locale whose source changed since its review says so when the dialog opens`() = myFixture.runWithConfig(config) {
        seedStale()

        withDialog { dialog ->
            Assertions.assertEquals(label(ShownReview.SOURCE_CHANGED), dialog.reviewStateOf("fr"))
            Assertions.assertEquals(PluginBundle.message("review.dialog.state.none"), dialog.reviewStateOf("en"), "the reference is never stale")
        }
    }

    @Test
    fun `a stale locale translated again and validated is to review, no longer stale`() = myFixture.runWithConfig(config) {
        val (fr, root) = seedStale()

        withDialog { dialog ->
            dialog.translateLocale("fr")
            dialog.confirm()
        }

        Assertions.assertTrue("Bonjour" in frText(fr), frText(fr))
        val review = store().reviews(root, "fr").getValue(key)
        Assertions.assertEquals(ReviewState.TO_REVIEW, review.state)
        Assertions.assertEquals(ReviewSource.of("en", "Hello"), review.source, "the source of the day")
        withDialog { dialog -> Assertions.assertEquals(label(ShownReview.TO_REVIEW), dialog.reviewStateOf("fr")) }
    }

    @Test
    fun `approving a stale locale from its Review link records the source of now, no longer stale`() = myFixture.runWithConfig(config) {
        val (_, root) = seedStale()
        onReviewThread { thread ->
            Assertions.assertEquals(ShownReview.SOURCE_CHANGED, thread.shownReview(), "the thread reads the source of now")
            Assertions.assertTrue(thread.approve())
        }

        withDialog { dialog ->
            Assertions.assertTrue(dialog.openReviewOf("fr"))
            Assertions.assertEquals(label(ShownReview.APPROVED), dialog.reviewStateOf("fr"), "the badge follows the decision")
        }

        val review = store().reviews(root, "fr").getValue(key)
        Assertions.assertEquals(ReviewState.APPROVED, review.state)
        Assertions.assertEquals(ReviewSource.of("en", "Hello"), review.source, "the source of the day")
        withDialog { dialog -> Assertions.assertEquals(label(ShownReview.APPROVED), dialog.reviewStateOf("fr")) }
    }

    @Test
    fun `a stale locale retouched by hand is not marked and reads changed since approval`() = myFixture.runWithConfig(config) {
        val (fr, root) = seedStale()

        withDialog { dialog ->
            dialog.translateLocale("fr")
            dialog.setValue("fr", "Bonjour !")
            dialog.confirm()
        }

        Assertions.assertTrue("Bonjour !" in frText(fr), frText(fr))
        val review = store().reviews(root, "fr").getValue(key)
        Assertions.assertEquals(ReviewState.APPROVED, review.state, "no mark")
        Assertions.assertEquals(ReviewSource.of("en", "Hi"), review.source)
        withDialog { dialog -> Assertions.assertEquals(label(ShownReview.CHANGED_SINCE_APPROVAL), dialog.reviewStateOf("fr")) }
    }

    @Test
    fun `one Ctrl+Z gives a stale locale its value and its review back`() = myFixture.runWithConfig(config) {
        val (fr, root) = seedStale()
        myFixture.openFileInEditor(fr)

        withDialog { dialog ->
            dialog.translateLocale("fr")
            dialog.confirm()
        }
        Assertions.assertEquals(ReviewState.TO_REVIEW, store().reviews(root, "fr").getValue(key).state)

        UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor!!)

        Assertions.assertTrue("Salut" in frText(fr), frText(fr))
        val review = store().reviews(root, "fr").getValue(key)
        Assertions.assertEquals(ReviewState.APPROVED, review.state)
        Assertions.assertEquals(ReviewSource.of("en", "Hi"), review.source)
        // The dialog reads values through the PSI, which an undo leaves to the next commit.
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        withDialog { dialog -> Assertions.assertEquals(label(ShownReview.SOURCE_CHANGED), dialog.reviewStateOf("fr")) }
    }

    /**
     * `ru`'s `item_few` approved against [fewSource]; English has no `few`, its `item_other` reads
     * `{{count}} items in all` now.
     */
    private fun seedPluralReview(fewSource: ReviewSource?) {
        addFileToProject("locales/en/common.json", """{"item_one": "{{count}} item", "item_other": "{{count}} items in all"}""")
        addFileToProject("locales/ru/common.json", """{"item_one": "{{count}} элемент", "item_few": "{{count}} элемента"}""")
        val root = reviewRoot(enabled = true)
        WriteAction.runAndWait<RuntimeException> {
            store().decide(root, "ru", ReviewKey("common", "item_few"), ReviewState.APPROVED, "{{count}} элемента", source = fewSource)
        }
    }

    @Test
    fun `a plural form English lacks turns stale when English other changes`() = myFixture.runWithConfig(config) {
        seedPluralReview(ReviewSource.of("en", "{{count}} items"))

        withDialog("common:item_few") { dialog -> Assertions.assertEquals(label(ShownReview.SOURCE_CHANGED), dialog.reviewStateOf("ru")) }
    }

    @Test
    fun `a plural form approved on English other as it reads now is not stale`() = myFixture.runWithConfig(config) {
        seedPluralReview(ReviewSource.of("en", "{{count}} items in all"))

        withDialog("common:item_few") { dialog -> Assertions.assertEquals(label(ShownReview.APPROVED), dialog.reviewStateOf("ru")) }
    }

    @Test
    fun `a plural form approved before sources were recorded is never stale`() = myFixture.runWithConfig(config) {
        seedPluralReview(null)

        withDialog("common:item_few") { dialog -> Assertions.assertEquals(label(ShownReview.APPROVED), dialog.reviewStateOf("ru")) }
    }

    /**
     * Two modules declaring different references: the key's own is the one translated from and
     * recorded — not the first module's, which the table would not compare, every mark then stale.
     */
    @Test
    fun `the source is the reference of the key's module, not another module's`() = myFixture.runWithConfig(
        config.copy(modules = listOf(
            ModuleConfig(name = "admin", rootDirectory = "src/apps/admin", referenceLocale = "de"),
            ModuleConfig(name = "web", rootDirectory = "src/apps/web", referenceLocale = "es"),
        ))
    ) {
        addFileToProject("apps/web/locales/en/common.json", """{"greeting": "Hello"}""")
        addFileToProject("apps/web/locales/de/common.json", """{"greeting": "Hallo"}""")
        addFileToProject("apps/web/locales/es/common.json", """{"greeting": "Hola"}""")
        addFileToProject("apps/web/locales/fr/common.json", "{}")
        val requests = mutableListOf<TranslationRequest>()
        TranslationDialog.machineTranslator = { engine("Bonjour", requests) }
        val root = reviewRoot(enabled = true)

        withDialog { dialog ->
            dialog.translateEmptyLocales()
            dialog.confirm()
        }

        Assertions.assertEquals(listOf("es" to "fr"), requests.map { it.source to it.target })
        Assertions.assertEquals(ReviewSource.of("es", "Hola"), store().reviews(root, "fr").getValue(key).source)
    }

    @Test
    fun `a proposal retouched by hand is not marked`() = myFixture.runWithConfig(config) {
        val fr = seed()
        val root = reviewRoot(enabled = true)

        withDialog { dialog ->
            dialog.translateEmptyLocales()
            dialog.setValue("fr", "Bonjour !")
            dialog.confirm()
        }

        Assertions.assertTrue("Bonjour !" in frText(fr), frText(fr))
        Assertions.assertEquals(emptyMap<ReviewKey, Review>(), store().reviews(root, "fr"))
    }

    @Test
    fun `a module without reviews gets no mark, no link and no folder`() = myFixture.runWithConfig(config) {
        val fr = seed()
        val root = reviewRoot(enabled = false)

        withDialog { dialog ->
            Assertions.assertNull(dialog.reviewStateOf("fr"), "no mark, no Review… link")
            Assertions.assertNull(dialog.reviewStateOf("en"))
            Assertions.assertFalse(dialog.openReviewOf("fr"), "no Review… link to click")
            dialog.translateEmptyLocales()
            dialog.confirm()
        }

        Assertions.assertTrue("Bonjour" in frText(fr), frText(fr))
        Assertions.assertNull(root.findChild(ReviewLocation.DIRECTORY), "nothing created for reviews")
    }

    @Test
    fun `each locale of a module keeping reviews shows its review state`() = myFixture.runWithConfig(config) {
        seed()
        val root = reviewRoot(enabled = true)
        WriteAction.runAndWait<RuntimeException> {
            store().mark(root, "en", mapOf(key to "Hello"), Review.machineOrigin("DeepL"))
        }

        withDialog { dialog ->
            Assertions.assertEquals(PluginBundle.message("review.state.to.review"), dialog.reviewStateOf("en"))
            Assertions.assertEquals(PluginBundle.message("review.dialog.state.none"), dialog.reviewStateOf("fr"))
        }
    }

    @Test
    fun `one Ctrl+Z undoes the value and its mark`() = myFixture.runWithConfig(config) {
        val fr = seed()
        val root = reviewRoot(enabled = true)
        myFixture.openFileInEditor(fr)

        withDialog { dialog ->
            dialog.translateEmptyLocales()
            dialog.confirm()
        }
        Assertions.assertEquals(setOf(key), store().reviews(root, "fr").keys)

        UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor!!)

        Assertions.assertFalse("Bonjour" in frText(fr), frText(fr))
        Assertions.assertEquals(emptyMap<ReviewKey, Review>(), store().reviews(root, "fr"), "the mark is undone with the value")
    }

    @Test
    fun `only the proposals written exactly as proposed are kept`() {
        val proposals = mapOf(
            "fr" to TranslationDialog.MachineProposal("Bonjour", "machine:DeepL"),
            "es" to TranslationDialog.MachineProposal("Hola", "machine:DeepL"),
            "de" to TranslationDialog.MachineProposal("Hallo", "machine:DeepL"),
        )
        val kept = TranslationDialog.untouchedProposals(proposals, mapOf("fr" to "Bonjour", "es" to "¡Hola!"))
        Assertions.assertEquals(setOf("fr"), kept.keys, "retouched (es) and not written (de) are left out")
    }
}
