package com.ibrahimdans.i18n.plugin.mcp

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * An agent reads what a locale lacks, or what went stale in it, and writes it with the plugin's
 * guards: nothing already there is replaced unless it is stale when written, a value losing a
 * placeholder or meant for an ICU plural is refused, and what is written is one undoable command,
 * marked to review only where the module keeps reviews.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class TranslationAgentToolsTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")

    private fun seed(): PsiFile {
        myFixture.addFileToProject(
            "locales/en/common.json",
            """{"title": "Title", "save": "Save {{what}}", "cancel": "Cancel", "item_one": "{{count}} item",
               "item_other": "{{count}} items", "count": "{n, plural, one {# file} other {# files}}"}"""
        )
        return myFixture.addFileToProject("locales/de/common.json", """{"title": "Titel", "save": ""}""")
    }

    private fun tools(root: VirtualFile? = null) = TranslationAgentTools(project).apply { reviewRootOf = { root } }

    private fun store(): ReviewStore = ReviewStore.getInstance(project).apply {
        now = { Instant.parse("2026-10-15T14:02:11Z") }
        reviewer = { "Alice <alice@example.com>" }
    }

    @Test
    fun `the project is one module, translated from its reference locale`() = myFixture.runWithConfig(config) {
        seed()

        val module = tools().modules().single()

        Assertions.assertEquals(TranslationAgentTools.PROJECT, module.name)
        Assertions.assertEquals("en", module.referenceLocale)
        Assertions.assertEquals(setOf("en", "de"), module.locales.toSet())
        Assertions.assertFalse(module.reviews)
    }

    @Test
    fun `what a locale lacks comes with its source text, plural forms and ICU plurals included`() = myFixture.runWithConfig(config) {
        seed()

        val page = tools().missing(null, "de", 100)

        Assertions.assertEquals("en", page.sourceLocale)
        Assertions.assertEquals(
            listOf("common:cancel", "common:count", "common:item_one", "common:item_other", "common:save"),
            page.items.map { it.key }
        )
        Assertions.assertEquals("Save {{what}}", page.items.single { it.key == "common:save" }.source)
        Assertions.assertNotNull(page.items.single { it.key == "common:count" }.skipped, "an ICU plural is listed, not to fill")
        Assertions.assertNull(page.items.single { it.key == "common:item_one" }.skipped)

        val first = tools().missing(null, "de", 2)
        Assertions.assertEquals(2, first.items.size)
        Assertions.assertEquals(5, first.total, "the total counts what the limit leaves out")
    }

    @Test
    fun `only the values filling a missing one with its placeholders are written, in one undoable command`() = myFixture.runWithConfig(config) {
        val de = seed()
        myFixture.openFileInEditor(de.virtualFile)

        val result = tools().write(
            null, "de",
            mapOf(
                "common:save" to "{{what}} speichern",
                "common:cancel" to "Abbrechen",
                "common:title" to "Überschrift",
                "common:item_one" to " ",
                "common:item_other" to "Elemente",
                "common:count" to "{n, plural, one {# Datei} other {# Dateien}}",
                "common:unknown" to "Unbekannt",
            ),
            "codex"
        )

        Assertions.assertEquals(listOf("common:cancel", "common:save"), result.written)
        Assertions.assertEquals(TranslationAgentTools.ALREADY_FILLED, result.rejected["common:title"])
        Assertions.assertEquals(TranslationAgentTools.EMPTY, result.rejected["common:item_one"])
        Assertions.assertTrue(result.rejected.getValue("common:item_other").startsWith(TranslationAgentTools.PLACEHOLDERS))
        Assertions.assertEquals(TranslationAgentTools.ICU, result.rejected["common:count"])
        Assertions.assertEquals(TranslationAgentTools.NOT_MISSING, result.rejected["common:unknown"])

        val document = FileDocumentManager.getInstance().getDocument(de.virtualFile)!!
        Assertions.assertTrue("{{what}} speichern" in document.text, document.text)
        Assertions.assertTrue("Abbrechen" in document.text, document.text)
        Assertions.assertTrue("Titel" in document.text && "Überschrift" !in document.text, "never replaced: ${document.text}")

        UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor!!)
        Assertions.assertTrue("Abbrechen" !in document.text && "speichern" !in document.text, "one Ctrl+Z: ${document.text}")
    }

    @Test
    fun `the reference locale and an unknown locale or module are refused`() = myFixture.runWithConfig(config) {
        seed()

        Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { tools().missing(null, "en", 10) }
        Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { tools().missing(null, "ja", 10) }
        Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { tools().write("web", "de", mapOf("common:cancel" to "Abbrechen"), "codex") }
    }

    @Test
    fun `values written are marked to review as the agent's where the module keeps reviews`() = myFixture.runWithConfig(config) {
        seed()
        val root = myFixture.tempDirFixture.findOrCreateDir("app")
        val store = store()
        WriteCommandAction.runWriteCommandAction(project) { store.enable(root) }

        tools(root).write(null, "de", mapOf("common:cancel" to "Abbrechen"), "codex")

        val review = store.reviews(root, "de").getValue(ReviewKey("common", "cancel"))
        Assertions.assertEquals(ReviewState.TO_REVIEW, review.state)
        Assertions.assertEquals("machine:codex", review.origin)
        Assertions.assertEquals(ReviewSource.of("en", "Cancel"), review.source, "the source the agent was given")
    }

    @Test
    fun `a plural form the agent translated from other is marked with the source of other`() = myFixture.runWithConfig(config) {
        seed()
        myFixture.addFileToProject("locales/ru/common.json", "{}")
        val root = myFixture.tempDirFixture.findOrCreateDir("app")
        val store = store()
        WriteCommandAction.runWriteCommandAction(project) { store.enable(root) }

        val result = tools(root).write(null, "ru", mapOf("common:item_one" to "{{count}} элемент", "common:item_few" to "{{count}} элемента"), "codex")

        Assertions.assertEquals(listOf("common:item_few", "common:item_one"), result.written)
        val reviews = store.reviews(root, "ru")
        Assertions.assertEquals(ReviewSource.of("en", "{{count}} item"), reviews.getValue(ReviewKey("common", "item_one")).source)
        Assertions.assertEquals(
            ReviewSource.of("en", "{{count}} items"), reviews.getValue(ReviewKey("common", "item_few")).source,
            "read from `other`, the source the table compares the form with"
        )
    }

    @Test
    fun `an agent never turns reviews on`() = myFixture.runWithConfig(config) {
        seed()
        val root = myFixture.tempDirFixture.findOrCreateDir("app")

        Assertions.assertFalse(tools(root).modules().single().reviews)
        tools(root).write(null, "de", mapOf("common:cancel" to "Abbrechen"), "codex")

        Assertions.assertFalse(store().isEnabled(root))
    }

    /** `de` approved on the English of the day, then `save` and `cancel` changed in English: both stale, `title` not. */
    private fun staleProject(): Pair<VirtualFile, PsiFile> {
        val en = myFixture.addFileToProject("locales/en/common.json", """{"title": "Title", "save": "Save {{what}}", "cancel": "Cancel"}""")
        val de = myFixture.addFileToProject("locales/de/common.json", """{"title": "Titel", "save": "{{what}} speichern", "cancel": "Abbrechen"}""")
        val root = myFixture.tempDirFixture.findOrCreateDir("app")
        val store = store()
        WriteCommandAction.runWriteCommandAction(project) {
            store.enable(root)
            store.decide(root, "de", ReviewKey("common", "title"), ReviewState.APPROVED, "Titel", source = ReviewSource.of("en", "Title"))
            store.decide(root, "de", ReviewKey("common", "save"), ReviewState.APPROVED, "{{what}} speichern", source = ReviewSource.of("en", "Save {{what}}"))
            store.decide(root, "de", ReviewKey("common", "cancel"), ReviewState.APPROVED, "Abbrechen", source = ReviewSource.of("en", "Cancel"))
        }
        edit(en, "\"Save {{what}}\"" to "\"Save all {{what}}\"", "\"Cancel\"" to "\"Cancel all\"")
        return root to de
    }

    private fun edit(file: PsiFile, vararg replacements: Pair<String, String>) = WriteCommandAction.runWriteCommandAction(project) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        document.setText(replacements.fold(document.text) { text, (from, to) -> text.replace(from, to) })
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    private fun text(file: PsiFile): String = PsiDocumentManager.getInstance(project).getDocument(file)!!.text

    @Test
    fun `the stale translations of a locale come with the source of now and the value they replace`() = myFixture.runWithConfig(config) {
        val (root, _) = staleProject()

        val page = tools(root).stale(null, "de", 100)

        Assertions.assertEquals("en", page.sourceLocale)
        Assertions.assertEquals(
            listOf(
                TranslationAgentTools.Stale("common:cancel", "Cancel all", "Abbrechen", null),
                TranslationAgentTools.Stale("common:save", "Save all {{what}}", "{{what}} speichern", null),
            ),
            page.items
        )
        val first = tools(root).stale(null, "de", 1)
        Assertions.assertEquals(listOf("common:cancel"), first.items.map { it.key })
        Assertions.assertEquals(2, first.total, "the total counts what the limit leaves out")
    }

    @Test
    fun `a module without reviews has no stale list, refused with the reason`() = myFixture.runWithConfig(config) {
        seed()

        val refused = Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { tools(myFixture.tempDirFixture.findOrCreateDir("app")).stale(null, "de", 10) }

        Assertions.assertEquals(TranslationAgentTools.REVIEWS_OFF, refused.message)
    }

    @Test
    fun `a value stale when written is replaced and marked to review on the new source, a filled one is not`() = myFixture.runWithConfig(config) {
        val (root, de) = staleProject()

        val result = tools(root).write(
            null, "de",
            mapOf("common:save" to "Alle {{what}} speichern", "common:title" to "Überschrift", "common:cancel" to "Alles abbrechen {{x}}"),
            "codex"
        )

        Assertions.assertEquals(listOf("common:save"), result.written)
        Assertions.assertEquals(TranslationAgentTools.ALREADY_FILLED, result.rejected["common:title"], "reviewed on the source of now: not stale")
        Assertions.assertTrue(result.rejected.getValue("common:cancel").startsWith(TranslationAgentTools.PLACEHOLDERS), "checked against the new source")
        val text = text(de)
        Assertions.assertTrue("\"Alle {{what}} speichern\"" in text && "\"Titel\"" in text && "\"Abbrechen\"" in text, text)
        val save = store().reviews(root, "de").getValue(ReviewKey("common", "save"))
        Assertions.assertEquals(ReviewState.TO_REVIEW, save.state)
        Assertions.assertEquals("machine:codex", save.origin)
        Assertions.assertEquals(ReviewSource.of("en", "Save all {{what}}"), save.source, "the new source: no longer stale")
        Assertions.assertEquals(listOf("common:cancel"), tools(root).stale(null, "de", 10).items.map { it.key })
    }

    @Test
    fun `a stale value fixed between the listing and the write is refused, never replaced`() = myFixture.runWithConfig(config) {
        val (root, de) = staleProject()
        Assertions.assertEquals(listOf("common:cancel", "common:save"), tools(root).stale(null, "de", 10).items.map { it.key })

        edit(de, "\"Abbrechen\"" to "\"Alles abbrechen\"")
        val result = tools(root).write(null, "de", mapOf("common:cancel" to "Alle abbrechen"), "codex")

        Assertions.assertEquals(emptyList<String>(), result.written)
        Assertions.assertEquals(TranslationAgentTools.ALREADY_FILLED, result.rejected["common:cancel"], "no longer stale when written")
        Assertions.assertTrue("\"Alles abbrechen\"" in text(de), text(de))
    }

    @Test
    fun `a stale value changed while being written is refused with its own reason, the others written`() = myFixture.runWithConfig(config) {
        val (root, de) = staleProject()
        val tools = tools(root).apply { beforeWrite = { edit(de, "\"Abbrechen\"" to "\"Alles abbrechen\"") } }

        val result = tools.write(null, "de", mapOf("common:cancel" to "Alle abbrechen", "common:save" to "Alle {{what}} speichern"), "codex")

        Assertions.assertEquals(listOf("common:save"), result.written)
        Assertions.assertEquals(TranslationAgentTools.CHANGED, result.rejected["common:cancel"])
        val text = text(de)
        Assertions.assertTrue("\"Alles abbrechen\"" in text && "\"Alle {{what}} speichern\"" in text, text)
        Assertions.assertEquals(ReviewState.APPROVED, store().reviews(root, "de").getValue(ReviewKey("common", "cancel")).state, "not marked")
    }
}
