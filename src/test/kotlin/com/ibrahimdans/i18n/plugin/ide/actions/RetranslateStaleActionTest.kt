package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableReviews
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewHash
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.translate.Cancellation
import com.ibrahimdans.i18n.plugin.translate.TranslatedBy
import com.ibrahimdans.i18n.plugin.translate.Translation
import com.ibrahimdans.i18n.plugin.translate.TranslationProvider
import com.ibrahimdans.i18n.plugin.translate.TranslationRequest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * *Retranslate Stale Translations*: only the values whose source changed since their review are
 * proposed, a value edited since the preview is never replaced, and what is written is marked to
 * review on the new source in the same command — one Ctrl+Z undoes values and reviews.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class RetranslateStaleActionTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")

    private val requests = ConcurrentLinkedQueue<TranslationRequest>()

    private val engine = object : TranslationProvider {
        override fun translate(request: TranslationRequest, cancellation: Cancellation?): List<Translation> {
            requests += request
            return request.texts.map { Translation.Done("[fr] $it", TranslatedBy("DeepL", false)) }
        }
    }

    private val icu = PluginBundle.message("action.fill.icu")

    private fun approved(value: String, source: String?) =
        Review(ReviewState.APPROVED, ReviewHash.of(value), "Alice", "2026-10-01T10:00:00Z", source = source?.let { ReviewSource.of("en", it) })

    @Test
    fun `only the values decided on a source changed since are proposed, from their own source`() {
        val translations = mapOf(
            "common:save" to mapOf("en" to "Save all", "fr" to "Enregistrer"),
            "common:title" to mapOf("en" to "Title", "fr" to "Titre"),
            "common:edited" to mapOf("en" to "Edit now", "fr" to "Modifié"),
            "common:old" to mapOf("en" to "Old", "fr" to "Vieux"),
            "common:empty" to mapOf("en" to "Empty", "fr" to ""),
            "common:item_one" to mapOf("en" to "{{count}} item!", "fr" to "{{count}} élément"),
            "common:count" to mapOf("en" to "{n, plural, one {# file} other {# files!}}", "fr" to "{n, plural, one {# fichier} other {# fichiers}}"),
        )
        val reviews = TableReviews(
            mapOf("fr" to mapOf(
                ReviewKey("common", "save") to approved("Enregistrer", "Save"),
                ReviewKey("common", "title") to approved("Titre", "Title"),
                ReviewKey("common", "edited") to approved("Modifier", "Edit"),
                ReviewKey("common", "old") to approved("Vieux", null),
                ReviewKey("common", "empty") to approved("", "Nothing"),
                ReviewKey("common", "item_one") to approved("{{count}} élément", "{{count}} item"),
                ReviewKey("common", "count") to approved("{n, plural, one {# fichier} other {# fichiers}}", "{n, plural, one {# file} other {# files}}"),
            )),
            "translation", referenceLocale = "en"
        )

        Assertions.assertEquals(
            listOf(
                MachineFill.Item("common:count", "{n, plural, one {# file} other {# files!}}", skipped = icu, current = "{n, plural, one {# fichier} other {# fichiers}}"),
                MachineFill.Item("common:item_one", "{{count}} item!", current = "{{count}} élément"),
                MachineFill.Item("common:save", "Save all", current = "Enregistrer"),
            ),
            MachineFill.staleItemsOf(translations, reviews, "fr"),
            "same source, value edited since, review without source, empty value: none is stale"
        )
        Assertions.assertTrue(MachineFill.staleItemsOf(translations, reviews, "fr").none { it.needsReview }, "a form is never translated from other")
        Assertions.assertEquals(emptyList<MachineFill.Item>(), MachineFill.staleItemsOf(translations, reviews, "en"), "not the reference locale")
        Assertions.assertEquals(emptyList<MachineFill.Item>(), MachineFill.staleItemsOf(translations, TableReviews.OFF, "fr"), "nothing without reviews")
    }

    /** English has no `few`: the Russian form decided on `item_other` is retranslated from it once it changed. */
    @Test
    fun `a plural form the reference lacks is retranslated from its other form, for review`() {
        val translations = mapOf(
            "common:item_one" to mapOf("en" to "{{count}} item", "ru" to "{{count}} элемент"),
            "common:item_few" to mapOf("ru" to "{{count}} элемента"),
            "common:item_other" to mapOf("en" to "{{count}} items in all", "ru" to "{{count}} элементов"),
        )
        val approvedRu = { value: String, source: String ->
            Review(ReviewState.APPROVED, ReviewHash.of(value), "Alice", "2026-10-01T10:00:00Z", source = ReviewSource.of("en", source))
        }
        val reviews = TableReviews(
            mapOf("ru" to mapOf(
                ReviewKey("common", "item_one") to approvedRu("{{count}} элемент", "{{count}} item"),
                ReviewKey("common", "item_few") to approvedRu("{{count}} элемента", "{{count}} items"),
            )),
            "translation", referenceLocale = "en"
        )

        Assertions.assertEquals(
            listOf(MachineFill.Item("common:item_few", "{{count}} items in all", needsReview = true, current = "{{count}} элемента")),
            MachineFill.staleItemsOf(translations, reviews, "ru")
        )
    }

    @Test
    fun `an ICU plural is listed, never sent`() {
        val items = listOf(MachineFill.Item("common:count", "{n, plural, one {# file} other {# files}}", skipped = icu, current = "{n, plural, other {# fichiers}}"))

        val proposals = MachineFill.translate(items, engine, "en", "fr", EmptyProgressIndicator())!!

        Assertions.assertTrue(requests.isEmpty(), "never sent")
        Assertions.assertEquals(listOf(icu), proposals.map { (it.result as Translation.Failed).reason })
        Assertions.assertEquals(emptyMap<String, Map<String, ReviewSource>>(), MachineFill.reviewSourcesOf(proposals, "en"), "nothing to mark")
    }

    /** The review store, signing and dating as a test can check. */
    private fun store(): ReviewStore = ReviewStore.getInstance(project).apply {
        now = { Instant.parse("2026-10-15T09:00:00Z") }
        reviewer = { "Alice <alice@example.com>" }
    }

    /** A module root of the fixture, standing for the one [ReviewLocation.rootOf] resolves. */
    private fun reviewRoot(): VirtualFile = myFixture.tempDirFixture.findOrCreateDir("app")

    /** `fr` approved on the English of the day, then the English changed: both keys stale. */
    private fun staleProject(): Pair<VirtualFile, PsiFile> {
        val en = myFixture.addFileToProject("locales/en/common.json", """{"save": "Save", "cancel": "Cancel"}""")
        val fr = myFixture.addFileToProject("locales/fr/common.json", """{"save": "Enregistrer", "cancel": "Annuler"}""")
        val root = reviewRoot()
        val store = store()
        WriteCommandAction.runWriteCommandAction(project) {
            store.enable(root)
            store.decide(root, "fr", ReviewKey("common", "save"), ReviewState.APPROVED, "Enregistrer", source = ReviewSource.of("en", "Save"))
            store.decide(root, "fr", ReviewKey("common", "cancel"), ReviewState.APPROVED, "Annuler", source = ReviewSource.of("en", "Cancel"))
        }
        edit(en, "\"Save\"" to "\"Save all\"", "\"Cancel\"" to "\"Cancel all\"")
        return root to fr
    }

    private fun edit(file: PsiFile, vararg replacements: Pair<String, String>) = WriteCommandAction.runWriteCommandAction(project) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        document.setText(replacements.fold(document.text) { text, (from, to) -> text.replace(from, to) })
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    private fun reviews(root: VirtualFile): TableReviews =
        TableViewModel().loadReviews(project, root, listOf("en", "fr"), Settings.getInstance(project).config())

    private fun shown(root: VirtualFile, key: String): ShownReview {
        // An undo changes the documents, not the PSI the loader reads.
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val values = TranslationDataLoader.loadAllTranslations(project, null).getValue(key)
        return reviews(root).shownOf(key, "fr", values["fr"], values["en"])
    }

    /** What the preview proposes for `fr`, as [MachineFill.retranslate] computes it. */
    private fun preview(root: VirtualFile): List<MachineFill.Proposal> {
        val items = MachineFill.staleItemsOf(TranslationDataLoader.loadAllTranslations(project, null), reviews(root), "fr")
        return MachineFill.translate(items, engine, "en", "fr", EmptyProgressIndicator())!!
    }

    private fun write(root: VirtualFile, proposals: List<MachineFill.Proposal>, checked: Set<String>) {
        val accepted = proposals.filter { it.item.key in checked }.associate { it.item.key to (it.result as Translation.Done).text }
        MachineFill.write(
            project, null, mapOf("fr" to accepted),
            MachineFill.ReviewMarks(root, mapOf("fr" to accepted.mapValues { Review.machineOrigin("DeepL") }), MachineFill.reviewSourcesOf(proposals, "en")),
            MachineFill.replacedOf(proposals),
            PluginBundle.message("action.retranslate.command")
        )
    }

    @Test
    fun `written values are marked to review on the new source, an unchecked key stays stale, one Ctrl+Z undoes it all`() = myFixture.runWithConfig(config) {
        val (root, fr) = staleProject()
        myFixture.openFileInEditor(fr.virtualFile)
        Assertions.assertEquals(ShownReview.SOURCE_CHANGED, shown(root, "common:save"))
        val proposals = preview(root)
        Assertions.assertEquals(listOf("common:cancel", "common:save"), proposals.map { it.item.key })

        write(root, proposals, checked = setOf("common:save"))

        val text = myFixture.editor.document.text
        Assertions.assertTrue("\"[fr] Save all\"" in text && "\"Annuler\"" in text, text)
        val save = store().reviews(root, "fr").getValue(ReviewKey("common", "save"))
        Assertions.assertEquals(ReviewState.TO_REVIEW, save.state)
        Assertions.assertEquals(ReviewSource.of("en", "Save all"), save.source)
        Assertions.assertEquals(ShownReview.TO_REVIEW, shown(root, "common:save"), "no longer stale")
        Assertions.assertEquals(ShownReview.SOURCE_CHANGED, shown(root, "common:cancel"), "unchecked: still stale")

        UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor!!)

        Assertions.assertTrue("\"Enregistrer\"" in myFixture.editor.document.text, myFixture.editor.document.text)
        Assertions.assertEquals(ReviewState.APPROVED, store().reviews(root, "fr").getValue(ReviewKey("common", "save")).state, "the review is restored too")
        Assertions.assertEquals(ShownReview.SOURCE_CHANGED, shown(root, "common:save"))
    }

    @Test
    fun `a value edited between the preview and the write is neither replaced nor marked`() = myFixture.runWithConfig(config) {
        val (root, fr) = staleProject()
        val proposals = preview(root)

        edit(fr, "\"Annuler\"" to "\"Tout annuler\"")
        write(root, proposals, checked = setOf("common:save", "common:cancel"))

        val text = PsiDocumentManager.getInstance(project).getDocument(fr)!!.text
        Assertions.assertTrue("\"Tout annuler\"" in text && "[fr] Cancel all" !in text, text)
        Assertions.assertTrue("\"[fr] Save all\"" in text, text)
        Assertions.assertEquals(ReviewState.APPROVED, store().reviews(root, "fr").getValue(ReviewKey("common", "cancel")).state, "not written, not marked")
    }

    @Test
    fun `without reviews the action is not offered`() = myFixture.runWithConfig(config) {
        Assertions.assertFalse(RetranslateStaleAction.reviewsOn(project), "no reviews folder anywhere")
        val root = ReviewLocation.rootOf(project, null) ?: return@runWithConfig
        WriteCommandAction.runWriteCommandAction(project) { store().enable(root) }
        Assertions.assertTrue(RetranslateStaleAction.reviewsOn(project), "reviews on under the project's directory")
    }
}
