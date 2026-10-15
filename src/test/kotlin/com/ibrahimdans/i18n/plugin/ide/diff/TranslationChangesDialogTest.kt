package com.ibrahimdans.i18n.plugin.ide.diff

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.MachineFill
import com.ibrahimdans.i18n.plugin.ide.dialog.MachineTranslationPreviewDialog
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.translate.TranslatedBy
import com.ibrahimdans.i18n.plugin.translate.Translation
import com.ibrahimdans.i18n.plugin.translate.TranslationProvider
import com.ibrahimdans.i18n.plugin.translate.TranslationRequest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * *Retranslate Lagging…* on the lagging view of the translation changes: the keys the reference
 * changed and a locale did not follow are translated again and written — without reviews too —
 * a value edited since the preview being left as it is.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class TranslationChangesDialogTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")

    private val engine = object : TranslationProvider {
        override fun translate(request: TranslationRequest, indicator: ProgressIndicator?): List<Translation> =
            request.texts.map { Translation.Done("[${request.target}] $it", TranslatedBy("DeepL", false)) }
    }

    /** The working copy's changes, given each changed file's text before the change. */
    private fun changes(vararg before: Pair<PsiFile, String?>): TranslationChanges = ReadAction.compute<TranslationChanges, RuntimeException> {
        val files = before.map { (file, text) ->
            LocalTranslationChanges.versionsOf(PsiManager.getInstance(project).findFile(file.virtualFile)!!, text)!!
        }
        LocalTranslationChanges.compare(project, files)
    }

    private fun preview(found: TranslationChanges): List<LaggingRetranslation.Preview> =
        LaggingRetranslation.translate(LaggingRetranslation.batchesOf(project, found), engine, "en", EmptyProgressIndicator())!!

    private fun text(file: PsiFile): String = PsiDocumentManager.getInstance(project).getDocument(file)!!.text

    private fun edit(file: PsiFile, from: String, to: String) = WriteCommandAction.runWriteCommandAction(project) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        document.setText(document.text.replace(from, to))
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    /** What the preview of [preview] writes, every proposal checked and the review box as it comes. */
    private fun write(preview: LaggingRetranslation.Preview, check: (MachineTranslationPreviewDialog) -> Unit = {}) {
        val root = ReviewLocation.rootOf(project, preview.module)
        val dialog = MachineTranslationPreviewDialog(project, "fr", preview.proposals, listOfNotNull(root))
        try {
            check(dialog)
            LaggingRetranslation.writeChecked(project, preview, "en", dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    @Test
    fun `a key the reference changed and a locale did not follow is proposed, then written without reviews`() = myFixture.runWithConfig(config) {
        val en = addFileToProject("locales/en/common.json", """{"save": "Save all", "cancel": "Cancel", "new": "New"}""")
        val fr = addFileToProject("locales/fr/common.json", """{"save": "Enregistrer", "cancel": "Annuler"}""")
        val found = changes(en to """{"save": "Save", "cancel": "Cancel"}""")

        val previews = preview(found)

        val proposals = previews.single().proposals
        Assertions.assertNull(previews.single().module, "a single scope without modules")
        Assertions.assertEquals(
            listOf(MachineFill.Item("common:new", "New"), MachineFill.Item("common:save", "Save all", current = "Enregistrer")),
            proposals.map { it.item },
            "the unchanged key is not proposed; the added one is filled, the changed one replaced"
        )
        write(previews.single()) { dialog ->
            Assertions.assertFalse(dialog.markForReview.isSelected, "reviews off: nothing marked")
            Assertions.assertTrue(dialog.markForReview.isEnabled, "and nothing locked")
        }

        val written = text(fr)
        Assertions.assertTrue("\"[fr] Save all\"" in written && "\"[fr] New\"" in written && "\"Annuler\"" in written, written)
        val root = ReviewLocation.rootOf(project, null)
        if (root != null) Assertions.assertFalse(ReviewStore.getInstance(project).isEnabled(root), "no reviews folder created")
    }

    @Test
    fun `a locale that followed the change is not proposed`() = myFixture.runWithConfig(config) {
        val en = addFileToProject("locales/en/common.json", """{"save": "Save all"}""")
        val fr = addFileToProject("locales/fr/common.json", """{"save": "Tout enregistrer"}""")

        val found = changes(en to """{"save": "Save"}""", fr to """{"save": "Enregistrer"}""")

        Assertions.assertEquals(emptyList<LaggingLocale>(), found.lagging)
        Assertions.assertEquals(emptyList<LaggingRetranslation.Batch>(), LaggingRetranslation.batchesOf(project, found))
    }

    @Test
    fun `a plural group followed through another form is not proposed, one left behind brings its forms`() = myFixture.runWithConfig(config) {
        val en = addFileToProject("locales/en/common.json", """{"item_one": "{{count}} item!", "item_other": "{{count}} items", "file_one": "{{count}} file!", "file_other": "{{count}} files"}""")
        val fr = addFileToProject("locales/fr/common.json", """{"item_one": "{{count}} élément", "item_other": "{{count}} éléments!", "file_one": "{{count}} fichier", "file_other": "{{count}} fichiers"}""")

        val found = changes(
            en to """{"item_one": "{{count}} item", "item_other": "{{count}} items", "file_one": "{{count}} file", "file_other": "{{count}} files"}""",
            fr to """{"item_one": "{{count}} élément", "item_other": "{{count}} éléments", "file_one": "{{count}} fichier", "file_other": "{{count}} fichiers"}""",
        )

        val keys = preview(found).single().proposals.map { it.item }
        Assertions.assertTrue(keys.none { it.key.startsWith("common:item_") }, "item followed through item_other: $keys")
        Assertions.assertTrue(MachineFill.Item("common:file_one", "{{count}} file!", current = "{{count}} fichier") in keys, keys.toString())
        Assertions.assertTrue(MachineFill.Item("common:file_other", "{{count}} files", current = "{{count}} fichiers") in keys, keys.toString())
    }

    @Test
    fun `a value edited between the preview and the write is not replaced`() = myFixture.runWithConfig(config) {
        val en = addFileToProject("locales/en/common.json", """{"save": "Save all", "cancel": "Cancel all"}""")
        val fr = addFileToProject("locales/fr/common.json", """{"save": "Enregistrer", "cancel": "Annuler"}""")
        val previews = preview(changes(en to """{"save": "Save", "cancel": "Cancel"}"""))

        edit(fr, "\"Annuler\"", "\"Tout annuler\"")
        write(previews.single())

        val written = text(fr)
        Assertions.assertTrue("\"Tout annuler\"" in written && "[fr] Cancel all" !in written, written)
        Assertions.assertTrue("\"[fr] Save all\"" in written, written)
    }

    @Test
    fun `a reference edited since the comparison is not proposed`() = myFixture.runWithConfig(config) {
        val en = addFileToProject("locales/en/common.json", """{"save": "Save all"}""")
        addFileToProject("locales/fr/common.json", """{"save": "Enregistrer"}""")
        val found = changes(en to """{"save": "Save"}""")

        edit(en, "\"Save all\"", "\"Save everything\"")

        Assertions.assertEquals(emptyList<LaggingRetranslation.Batch>(), LaggingRetranslation.batchesOf(project, found))
    }

    /**
     * The roots name `src/`: the fixture's files live under an in-memory `/src`, which the loader's
     * project-relative path keeps, disjoint from the project's base path.
     */
    @Test
    fun `keys go by module, a namesake in another module is left alone`() = myFixture.runWithConfig(
        config.copy(modules = listOf(ModuleConfig(name = "web", rootDirectory = "src/apps/web"), ModuleConfig(name = "admin", rootDirectory = "src/apps/admin")))
    ) {
        val en = addFileToProject("apps/web/locales/en/common.json", """{"title": "Web app"}""")
        addFileToProject("apps/web/locales/fr/common.json", """{"title": "Web"}""")
        addFileToProject("apps/admin/locales/en/common.json", """{"title": "Admin"}""")
        val adminFr = addFileToProject("apps/admin/locales/fr/common.json", """{"title": "Administration"}""")

        val previews = preview(changes(en to """{"title": "Web"}"""))

        Assertions.assertEquals(listOf("web"), previews.map { it.module?.name })
        Assertions.assertEquals(listOf(MachineFill.Item("common:title", "Web app", current = "Web")), previews.single().proposals.map { it.item })
        write(previews.single())
        Assertions.assertTrue("\"Administration\"" in text(adminFr), text(adminFr))
    }

    @Test
    fun `the button shows on the lagging view only`() = myFixture.runWithConfig(config) {
        val en = addFileToProject("locales/en/common.json", """{"save": "Save all"}""")
        addFileToProject("locales/fr/common.json", """{"save": "Enregistrer"}""")
        val dialog = TranslationChangesDialog(project, changes(en to """{"save": "Save"}"""))
        try {
            Assertions.assertFalse(dialog.retranslate.isVisible, "all the changes: nothing to retranslate from there")
            dialog.showLaggingOnly(true)
            Assertions.assertTrue(dialog.retranslate.isVisible)
            Assertions.assertEquals(PluginBundle.message("diff.translation.retranslate"), dialog.retranslate.text)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }
}
