package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.MachineFill
import com.ibrahimdans.i18n.plugin.ide.dialog.MachineTranslationPreviewDialog
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewHash
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.translate.TranslatedBy
import com.ibrahimdans.i18n.plugin.translate.Translation
import com.ibrahimdans.i18n.plugin.translate.TranslationProvider
import com.ibrahimdans.i18n.plugin.translate.TranslationRequest
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * *Internationalize…* with machine translation: the other locales get the new keys translated, in
 * the same command as the reference values, and a proposal the engine got wrong leaves its locale empty.
 */
class I18nizeTranslateTest : PlatformBaseTest() {

    private val config = Config(preferredLocalization = "json", defaultNs = "common")

    private fun engine(answer: (String) -> Translation) = object : TranslationProvider {
        override fun translate(request: TranslationRequest, indicator: ProgressIndicator?): List<Translation> =
            request.texts.map(answer)
    }

    private fun preview(vararg files: PsiFile): I18nizeAction.Preview = ReadAction.compute<I18nizeAction.Preview, RuntimeException> {
        I18nizeAction.compute(project, files.map { it.virtualFile }, EmptyProgressIndicator())
    }

    private fun itemsOf(preview: I18nizeAction.Preview) = ReadAction.compute<Map<Pair<String, String>, List<MachineFill.Item>>, RuntimeException> {
        I18nizeAction.translationItems(project, preview.plan, preview.existing)
    }

    /** What the *Fill Missing Translations* preview accepts as it opens: the checked proposals. */
    private fun accepted(proposals: List<MachineFill.Proposal>): Map<String, Map<String, String>> {
        val dialog = MachineTranslationPreviewDialog(project, "fr", proposals)
        try {
            return dialog.acceptedByLocale()
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    private fun write(plan: I18nizePlan, translations: Map<String, Map<String, String>>, reviewOrigins: Map<String, Map<String, String>>? = null) =
        ReadAction.compute<I18nizeApply, RuntimeException> { I18nizeApply.prepare(plan, translations) }.write("Internationalize", reviewOrigins)

    private fun textOf(file: PsiFile): String = PsiDocumentManager.getInstance(project).getDocument(file)!!.text

    private fun menu() = myFixture.addFileToProject("src/Menu.tsx", "export function Menu() { return <nav><p>Home</p><p>Settings</p></nav>; }")

    @Test
    fun theCheckBoxIsThereOnlyWhenTheProjectOptedIn() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", "{}")
        val preview = preview(menu())

        listOf(false, true).forEach { translatable ->
            val dialog = I18nizePreviewDialog(project, I18nizePreviewModel(preview.plan, preview.existing, config), translatable)
            try {
                Assertions.assertEquals(translatable, dialog.translateBox != null)
                Assertions.assertEquals(translatable, dialog.translateOthers())
            } finally {
                Disposer.dispose(dialog.disposable)
            }
        }
    }

    @Test
    fun translatedValuesAreWrittenWithTheReferenceInOneUndo() = myFixture.runWithConfig(config) {
        val en = myFixture.addFileToProject("locales/en/common.json", "{}")
        val fr = myFixture.addFileToProject("locales/fr/common.json", "{}")
        val menu = menu()
        val before = textOf(menu)
        myFixture.configureFromExistingVirtualFile(menu.virtualFile)
        val preview = preview(menu)

        val items = itemsOf(preview)
        Assertions.assertEquals(setOf("en" to "fr"), items.keys)
        val proposals = I18nizeAction.translate(items, engine { Translation.Done("[fr] $it") }, null)!!
        write(preview.plan, accepted(proposals))

        listOf("\"home\": \"Home\"", "\"settings\": \"Settings\"").forEach { Assertions.assertTrue(it in textOf(en), textOf(en)) }
        listOf("\"home\": \"[fr] Home\"", "\"settings\": \"[fr] Settings\"").forEach { Assertions.assertTrue(it in textOf(fr), textOf(fr)) }

        UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor!!)

        Assertions.assertEquals(before, textOf(menu))
        Assertions.assertEquals("{}", textOf(en))
        Assertions.assertEquals("{}", textOf(fr))
    }

    @Test
    fun aRefusedProposalLeavesItsLocaleEmpty() = myFixture.runWithConfig(config) {
        val en = myFixture.addFileToProject("locales/en/common.json", "{}")
        val fr = myFixture.addFileToProject("locales/fr/common.json", "{}")
        val preview = preview(menu())

        val proposals = I18nizeAction.translate(
            itemsOf(preview),
            engine { if (it == "Home") Translation.Failed("a variable was lost") else Translation.Done("Paramètres") },
            null
        )!!
        write(preview.plan, accepted(proposals))

        Assertions.assertTrue("\"home\": \"Home\"" in textOf(en), textOf(en))
        Assertions.assertTrue("\"settings\": \"Paramètres\"" in textOf(fr), textOf(fr))
        Assertions.assertFalse("home" in textOf(fr), textOf(fr))
    }

    @Test
    fun aReusedKeyIsNotSentToTheEngine() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", """{ "nav": { "home": "Home" } }""")
        myFixture.addFileToProject("locales/fr/common.json", "{}")
        val preview = preview(menu())

        Assertions.assertEquals(listOf("menu.settings"), itemsOf(preview).values.flatten().map { it.key })
    }

    @Test
    fun translatedValuesAreMarkedForReviewInTheirLocalesOnly() = myFixture.runWithConfig(config) {
        val en = myFixture.addFileToProject("locales/en/common.json", "{}")
        myFixture.addFileToProject("locales/fr/common.json", "{}")
        val menu = menu()
        myFixture.configureFromExistingVirtualFile(menu.virtualFile)
        val root = ReviewLocation.rootOf(project, null)!!
        val store = ReviewStore.getInstance(project).apply { reviewer = { "Alice <alice@example.com>" } }
        val preview = preview(menu)
        val proposals = I18nizeAction.translate(itemsOf(preview), engine { Translation.Done("[fr] $it", TranslatedBy("DeepL", false)) }, null)!!

        val dialog = MachineTranslationPreviewDialog(project, "fr", proposals, listOf(root))
        val (accepted, origins) = try {
            Assertions.assertFalse(dialog.markForReview.isSelected)
            dialog.markForReview.isSelected = true
            dialog.acceptedByLocale() to dialog.reviewOrigins()
        } finally {
            Disposer.dispose(dialog.disposable)
        }
        write(preview.plan, accepted, origins)

        val reviews = store.reviews(root, "fr")
        Assertions.assertEquals(2, reviews.size, reviews.toString())
        reviews.forEach { (key, review) ->
            Assertions.assertEquals("common", key.namespace)
            Assertions.assertEquals(ReviewState.TO_REVIEW, review.state)
            Assertions.assertEquals("machine:DeepL", review.origin)
        }
        val home = reviews.entries.single { it.key.path.endsWith("home") }
        Assertions.assertEquals(ReviewHash.of("[fr] Home"), home.value.hash)
        Assertions.assertEquals(emptyMap<ReviewKey, Review>(), store.reviews(root, "en"), "the reference value, the code's text, is never marked")
        Assertions.assertNull(root.findFileByRelativePath(ReviewLocation.DIRECTORY + "/en.json"))
        Assertions.assertTrue("Home" in textOf(en))

        UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor!!)
        Assertions.assertEquals(emptyMap<ReviewKey, Review>(), store.reviews(root, "fr"), "one Ctrl+Z undoes the marks too")
    }

    @Test
    fun withoutTheBoxNoReviewIsWritten() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", "{}")
        myFixture.addFileToProject("locales/fr/common.json", "{}")
        val root = ReviewLocation.rootOf(project, null)!!
        val preview = preview(menu())
        val proposals = I18nizeAction.translate(itemsOf(preview), engine { Translation.Done("[fr] $it") }, null)!!

        write(preview.plan, accepted(proposals), null)

        Assertions.assertNull(root.findChild(ReviewLocation.DIRECTORY))
    }
}
