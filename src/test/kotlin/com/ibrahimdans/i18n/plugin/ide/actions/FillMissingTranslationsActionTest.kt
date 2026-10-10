package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.dialog.MachineTranslationPreviewDialog
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.translate.Translation
import com.ibrahimdans.i18n.plugin.translate.TranslationProvider
import com.ibrahimdans.i18n.plugin.translate.TranslationRequest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Disposer
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * *Fill Missing Translations*: only the keys a locale lacks go to the engine, a refused proposal is
 * never written, an existing value never replaced, and the writing is one undoable command.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class FillMissingTranslationsActionTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")

    private val requests = ConcurrentLinkedQueue<TranslationRequest>()

    private fun engine(answer: (String) -> Translation) = object : TranslationProvider {
        override fun translate(request: TranslationRequest, indicator: ProgressIndicator?): List<Translation> {
            requests += request
            return request.texts.map(answer)
        }
    }

    private val translations = mapOf(
        "common:title" to mapOf("en" to "Title", "fr" to "Titre"),
        "common:save" to mapOf("en" to "Save {{what}}", "fr" to ""),
        "common:cancel" to mapOf("en" to "Cancel"),
        "common:empty" to mapOf("en" to "", "fr" to ""),
        "common:item_one" to mapOf("en" to "{{count}} item"),
        "common:item_other" to mapOf("en" to "{{count}} items"),
        "common:count" to mapOf("en" to "{n, plural, one {# file} other {# files}}"),
    )

    private val icu = PluginBundle.message("action.fill.icu")

    @Test
    fun `only the keys the target lacks go, plural forms by the target's own`() {
        val items = MachineFill.itemsOf(translations, "en", "fr", ".")

        Assertions.assertEquals(
            listOf(
                MachineFill.Item("common:cancel", "Cancel"),
                MachineFill.Item("common:count", "{n, plural, one {# file} other {# files}}", skipped = icu),
                MachineFill.Item("common:item_one", "{{count}} item"),
                MachineFill.Item("common:item_other", "{{count}} items"),
                MachineFill.Item("common:save", "Save {{what}}"),
            ),
            items.filter { it.key.substringAfterLast('_') != "many" },
            "French gets `one` and `other` from the forms of the same name"
        )
    }

    private val plural = mapOf(
        "files:file_one" to mapOf("en" to "{{count}} file"),
        "files:file_other" to mapOf("en" to "{{count}} files"),
    )

    @Test
    fun `Russian gets four forms, the two English lacks marked for review`() {
        val items = MachineFill.itemsOf(plural, "en", "ru", ".")

        Assertions.assertEquals(
            listOf(
                MachineFill.Item("files:file_few", "{{count}} files", needsReview = true),
                MachineFill.Item("files:file_many", "{{count}} files", needsReview = true),
                MachineFill.Item("files:file_one", "{{count}} file"),
                MachineFill.Item("files:file_other", "{{count}} files"),
            ),
            items
        )
    }

    @Test
    fun `Japanese gets only other, and a form already there is not asked again`() {
        Assertions.assertEquals(listOf(MachineFill.Item("files:file_other", "{{count}} files")), MachineFill.itemsOf(plural, "en", "ja", "."))

        val withRussianOne = plural + ("files:file_one" to mapOf("en" to "{{count}} file", "ru" to "{{count}} файл"))
        Assertions.assertFalse(MachineFill.itemsOf(withRussianOne, "en", "ru", ".").any { it.key == "files:file_one" })
    }

    @Test
    fun `a nested group is a plural, a lone category name is an ordinary key`() {
        val nested = mapOf(
            "common:steps.one" to mapOf("en" to "One step"),
            "common:steps.other" to mapOf("en" to "{{count}} steps"),
            "common:level.one" to mapOf("en" to "Level one"),
        )
        val items = MachineFill.itemsOf(nested, "en", "ja", ".")

        Assertions.assertEquals(listOf("common:level.one", "common:steps.other"), items.map { it.key })
        Assertions.assertFalse(items.first().needsReview)
    }

    @Test
    fun `each key is translated with itself as context, refusals kept as such`() {
        val items = MachineFill.itemsOf(translations, "en", "fr", ".").filter { it.key == "common:cancel" || it.key == "common:save" }
        val proposals = MachineFill.translate(
            items, engine { if ("Cancel" in it) Translation.Failed("quota") else Translation.Done("Enregistrer {{what}}") },
            "en", "fr", EmptyProgressIndicator()
        )!!

        Assertions.assertEquals(listOf(Translation.Failed("quota"), Translation.Done("Enregistrer {{what}}")), proposals.map { it.result })
        Assertions.assertEquals(2, requests.size)
        Assertions.assertTrue(requests.all { r -> r.source == "en" && r.target == "fr" && r.context.endsWith(items.first { it.source == r.texts.single() }.key) })
    }

    @Test
    fun `a cancelled run proposes nothing`() {
        val indicator = EmptyProgressIndicator().apply { cancel() }

        Assertions.assertNull(MachineFill.translate(MachineFill.itemsOf(translations, "en", "fr", "."), engine { Translation.Done("x") }, "en", "fr", indicator))
    }

    @Test
    fun `an ICU plural is never sent and says why`() {
        val item = MachineFill.Item("common:count", "{n, plural, one {# file} other {# files}}", skipped = icu)
        val proposals = MachineFill.translate(listOf(item), engine { Translation.Done("x") }, "en", "fr", EmptyProgressIndicator())!!

        Assertions.assertEquals(listOf(Translation.Failed(icu)), proposals.map { it.result })
        Assertions.assertTrue(requests.isEmpty())
    }

    @Test
    fun `a refused proposal comes unchecked and is never accepted`() = myFixture.runWithConfig(config) {
        val proposals = listOf(
            MachineFill.Proposal(MachineFill.Item("common:save", "Save"), Translation.Done("Enregistrer")),
            MachineFill.Proposal(MachineFill.Item("common:cancel", "Cancel"), Translation.Failed("quota")),
            MachineFill.Proposal(MachineFill.Item("files:file_few", "{{count}} files", needsReview = true), Translation.Done("{{count}} файла")),
        )
        val dialog = MachineTranslationPreviewDialog(project, "fr", proposals)
        try {
            Assertions.assertEquals(mapOf("common:save" to "Enregistrer"), dialog.accepted())
            Assertions.assertFalse(dialog.model.isCellEditable(1, 0), "a refused proposal cannot be checked")
            Assertions.assertEquals(false, dialog.model.getValueAt(2, 0), "a form to review comes unchecked")
            Assertions.assertEquals(PluginBundle.message("action.fill.review"), dialog.model.getValueAt(2, 4))
            Assertions.assertTrue(dialog.model.isCellEditable(2, 0), "and can be checked once reviewed")
            dialog.model.setValueAt(false, 0, 0)
            Assertions.assertEquals(emptyMap<String, String>(), dialog.accepted())
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    @Test
    fun `accepted values are written in one undoable command, existing ones untouched`() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"title": "Title", "save": "Save", "cancel": "Cancel"}""")
        val fr = addFileToProject("locales/fr/common.json", """{"title": "Titre", "save": ""}""")
        myFixture.openFileInEditor(fr.virtualFile)

        MachineFill.write(project, null, "fr", mapOf("common:save" to "Enregistrer", "common:cancel" to "Annuler", "common:title" to "Intitulé"))

        val written = myFixture.editor.document.text
        Assertions.assertTrue("\"Enregistrer\"" in written && "\"Annuler\"" in written, written)
        Assertions.assertTrue("\"Titre\"" in written && "Intitulé" !in written, "an existing value is never replaced: $written")

        val editor = FileEditorManager.getInstance(project).selectedEditor!!
        UndoManager.getInstance(project).undo(editor)
        val undone = myFixture.editor.document.text
        Assertions.assertTrue("Enregistrer" !in undone && "Annuler" !in undone, "one Ctrl+Z undoes it all: $undone")
    }
}
