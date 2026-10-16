package com.ibrahimdans.i18n.plugin.review

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.toolwindow.ReviewBadge
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableReviews
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.mcp.TranslationAgentTools
import com.ibrahimdans.i18n.plugin.review.ui.ReviewActions
import com.ibrahimdans.i18n.plugin.review.ui.ReviewCell
import com.ibrahimdans.i18n.plugin.translate.TranslatedBy
import com.ibrahimdans.i18n.plugin.translate.Translation
import com.ibrahimdans.i18n.plugin.translate.TranslationProvider
import com.ibrahimdans.i18n.plugin.translate.TranslationRequest
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * One configured module declaring a reference locale (`de`) other than the preview locale (`en`).
 * The tool window, *Fill*, *Retranslate Stale…* and the agents read the project as one scope (no
 * module) while the key dialog takes the key's module: all of them must translate from, record and
 * compare the same locale, in the same reviews folder — the one already there.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class SingleModuleReviewScopeTest : PlatformBaseTest() {

    private val module = ModuleConfig(name = "app", rootDirectory = "src/locales", referenceLocale = "de")
    private val config = Config(defaultNs = "translation", previewLocale = "en", modules = listOf(module))
    private val originalTranslator = TranslationDialog.machineTranslator
    private val key = ReviewKey("common", "greeting")

    @AfterEach
    fun restore() {
        TranslationDialog.machineTranslator = originalTranslator
    }

    private fun store(): ReviewStore = ReviewStore.getInstance(project).apply {
        now = { Instant.parse("2026-10-15T10:00:00Z") }
        reviewer = { "Alice <alice@example.com>" }
    }

    /** `en`, `de` and `fr` hold the key; reviews on in the project's directory, where they are today. */
    private fun seed(fr: String = "Salut"): PsiFile {
        addFileToProject("locales/en/common.json", """{"greeting": "Hello"}""")
        val de = addFileToProject("locales/de/common.json", """{"greeting": "Hallo"}""")
        addFileToProject("locales/fr/common.json", """{"greeting": "$fr"}""")
        WriteCommandAction.runWriteCommandAction(project) { store().enable(project.guessProjectDir()!!) }
        return de
    }

    private fun changeSource(de: PsiFile) = WriteCommandAction.runWriteCommandAction(project) {
        val document = PsiDocumentManager.getInstance(project).getDocument(de)!!
        document.setText(document.text.replace("Hallo", "Guten Tag"))
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    /** The reviews as the table — and *Retranslate Stale…*, which reads them the same way — load them. */
    private fun tableReviews(): TableReviews {
        val locales = TranslationDataLoader.discoverLocales(project, null)
        return TableViewModel().loadReviews(project, ReviewLocation.rootOf(project, null), locales, config, null)
    }

    private fun tableState(): ShownReview {
        val values = TranslationDataLoader.loadAllTranslations(project, null)
        return tableReviews().shownOf("common:greeting", "fr", values["common:greeting"]?.get("fr"), values["common:greeting"]?.get("de"))
    }

    private fun withDialog(block: (TranslationDialog) -> Unit) {
        val dialog = TranslationDialog(project, KeysSynchronizer().buildFullKey("common:greeting", config), Mode.EDIT)
        try {
            block(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    @Test
    fun `the lone module and no module find the reviews folder already there`() = myFixture.runWithConfig(config) {
        seed()
        val projectDir = project.guessProjectDir()!!

        // The module's own root does not resolve in a light fixture: before, the dialog found no reviews at all.
        Assertions.assertEquals(projectDir, ReviewLocation.rootOf(project, null))
        Assertions.assertEquals(projectDir, ReviewLocation.rootOf(project, module))
    }

    @Test
    fun `no module stands for the lone module's reference locale, as the key's module does`() = myFixture.runWithConfig(config) {
        seed()
        val locales = TranslationDataLoader.discoverLocales(project, null)

        Assertions.assertEquals("de", ReferenceLocale.of(null, config, locales), "table, Fill and Retranslate")
        Assertions.assertEquals("de", tableReviews().referenceLocale)
        Assertions.assertEquals("de", TranslationAgentTools(project).modules().single().referenceLocale, "agents")
    }

    @Test
    fun `a value approved in the table reads source changed in the dialog once the source changed`() = myFixture.runWithConfig(config) {
        val de = seed()
        val reviews = tableReviews()
        Assertions.assertTrue(
            ReviewActions.approve(
                project, ReviewLocation.rootOf(project, null)!!, listOf(ReviewCell("common:greeting", "fr", "Salut", source = "Hallo")),
                referenceLocale = reviews.referenceLocale
            )
        )
        Assertions.assertEquals(ReviewSource.of("de", "Hallo"), store().reviews(project.guessProjectDir()!!, "fr").getValue(key).source)
        withDialog { Assertions.assertEquals(ReviewBadge.label(ShownReview.APPROVED), it.reviewStateOf("fr")) }

        changeSource(de)

        withDialog { Assertions.assertEquals(ReviewBadge.label(ShownReview.SOURCE_CHANGED), it.reviewStateOf("fr")) }
        Assertions.assertEquals(ShownReview.SOURCE_CHANGED, tableState())
    }

    @Test
    fun `a value the dialog translated reads source changed in the table and for the agents once the source changed`() =
        myFixture.runWithConfig(config) {
            val de = seed(fr = "")
            val requests = mutableListOf<TranslationRequest>()
            TranslationDialog.machineTranslator = {
                object : TranslationProvider {
                    override fun translate(request: TranslationRequest, indicator: ProgressIndicator?): List<Translation> {
                        requests += request
                        return listOf(Translation.Done("Bonjour", TranslatedBy("DeepL", local = false)))
                    }
                }
            }

            withDialog { dialog ->
                dialog.translateLocale("fr")
                dialog.confirm()
            }

            Assertions.assertEquals(listOf("de" to "fr"), requests.map { it.source to it.target })
            val root: VirtualFile = project.guessProjectDir()!!
            Assertions.assertEquals(ReviewSource.of("de", "Hallo"), store().reviews(root, "fr").getValue(key).source)
            Assertions.assertEquals(ShownReview.TO_REVIEW, tableState())

            changeSource(de)

            Assertions.assertEquals(ShownReview.SOURCE_CHANGED, tableState(), "table and Retranslate Stale")
            val stale = TranslationAgentTools(project).stale(null, "fr", 10)
            Assertions.assertEquals("de", stale.sourceLocale)
            Assertions.assertEquals(listOf(TranslationAgentTools.Stale("common:greeting", "Guten Tag", "Bonjour", null)), stale.items)
        }
}
