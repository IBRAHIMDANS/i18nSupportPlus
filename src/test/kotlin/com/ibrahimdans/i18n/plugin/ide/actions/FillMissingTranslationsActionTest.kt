package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.dialog.MachineTranslationPreviewDialog
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewHash
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewSources
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.review.shown
import com.ibrahimdans.i18n.plugin.translate.Cancellation
import com.ibrahimdans.i18n.plugin.translate.TranslatedBy
import com.ibrahimdans.i18n.plugin.translate.Translation
import com.ibrahimdans.i18n.plugin.translate.TranslationProvider
import com.ibrahimdans.i18n.plugin.translate.TranslationRequest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

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
        override fun translate(request: TranslationRequest, cancellation: Cancellation?): List<Translation> {
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

    /** `en-GB` inherits from `en` (#767): a key it has no entry for is not filled, a key it holds blank is. */
    @Test
    fun `a regional target does not receive the keys its language holds`() {
        val regional = mapOf(
            "common:save" to mapOf("en-US" to "Save", "en" to "Save"),
            "common:colour" to mapOf("en-US" to "Color", "en" to "Color", "en-GB" to ""),
            "common:only" to mapOf("en-US" to "Only US"),
            "common:item_one" to mapOf("en-US" to "{{count}} item", "en" to "{{count}} item"),
            "common:item_other" to mapOf("en-US" to "{{count}} items", "en" to "{{count}} items"),
        )

        Assertions.assertEquals(
            listOf("common:colour", "common:only"),
            MachineFill.itemsOf(regional, "en-US", "en-GB", ".", parent = "en").map { it.key },
            "save and the item plural are inherited from en; colour is blank in en-GB, only is absent from en",
        )
        Assertions.assertEquals(
            listOf("common:colour", "common:item_one", "common:item_other", "common:only", "common:save"),
            MachineFill.itemsOf(regional, "en-US", "en-GB", ".").map { it.key },
            "without a parent, every key en-GB lacks goes",
        )
    }

    @Test
    fun `only the keys the target lacks go, plural forms by the target's own`() {
        val items = MachineFill.itemsOf(translations, "en", "fr", ".")

        Assertions.assertEquals(
            listOf(
                MachineFill.Item("common:cancel", "Cancel"),
                MachineFill.Item("common:count", "{n, plural, one {# file} other {# files}}"),
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

    private val ordinal = mapOf(
        "race:place_one" to mapOf("en" to "{{count}} place"),
        "race:place_other" to mapOf("en" to "{{count}} places"),
        "race:place_ordinal_one" to mapOf("en" to "{{count}}st"),
        "race:place_ordinal_two" to mapOf("en" to "{{count}}nd"),
        "race:place_ordinal_few" to mapOf("en" to "{{count}}rd"),
        "race:place_ordinal_other" to mapOf("en" to "{{count}}th"),
    )

    @Test
    fun `an ordinal group into Russian gets other alone, its cardinal neighbour keeps four forms`() {
        Assertions.assertEquals(
            listOf("race:place_few", "race:place_many", "race:place_one", "race:place_ordinal_other", "race:place_other"),
            MachineFill.itemsOf(ordinal, "en", "ru", ".").map { it.key },
        )
    }

    @Test
    fun `an ordinal group into English gets two and few, from other to review`() {
        val french = mapOf(
            "race:place_ordinal_one" to mapOf("fr" to "{{count}}er"),
            "race:place_ordinal_other" to mapOf("fr" to "{{count}}e"),
        )
        Assertions.assertEquals(
            listOf(
                MachineFill.Item("race:place_ordinal_few", "{{count}}e", needsReview = true),
                MachineFill.Item("race:place_ordinal_one", "{{count}}er"),
                MachineFill.Item("race:place_ordinal_other", "{{count}}e"),
                MachineFill.Item("race:place_ordinal_two", "{{count}}e", needsReview = true),
            ),
            MachineFill.itemsOf(french, "fr", "en", "."),
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
    fun `the keys go together, their keys in order as context, refusals kept as such`() {
        val items = MachineFill.itemsOf(translations, "en", "fr", ".").filter { it.key == "common:cancel" || it.key == "common:save" }
        val proposals = MachineFill.translate(
            items, engine { if ("Cancel" in it) Translation.Failed("quota") else Translation.Done("Enregistrer {{what}}") },
            "en", "fr", EmptyProgressIndicator()
        )!!

        Assertions.assertEquals(listOf(Translation.Failed("quota"), Translation.Done("Enregistrer {{what}}")), proposals.map { it.result })
        val request = requests.single()
        Assertions.assertEquals(listOf("Cancel", "Save {{what}}"), request.texts, "one call for both keys")
        Assertions.assertTrue(request.source == "en" && request.target == "fr")
        Assertions.assertTrue(request.context.endsWith("common:cancel, common:save"), request.context)
    }

    @Test
    fun `the context carries what the module already translates, never a value being replaced`() {
        val known = mapOf(
            "common:form.save" to mapOf("en" to "Save {{what}}"),
            "common:form.reset" to mapOf("en" to "Reset", "fr" to "Réinitialiser"),
            "common:form.clear" to mapOf("en" to "Clear", "fr" to "Effacer à l'ancienne"),
            "common:menu.saveAll" to mapOf("en" to "Save all", "fr" to "Tout enregistrer"),
            "common:menu.quit" to mapOf("en" to "Quit", "fr" to "Quitter"),
        )
        val items = listOf(MachineFill.Item("common:form.save", "Save {{what}}"), MachineFill.Item("common:form.clear", "Clear", current = "Effacer à l'ancienne"))
        MachineFill.translate(items, engine { Translation.Done("x") }, "en", "fr", EmptyProgressIndicator(), known, config)!!

        val context = requests.single().context
        Assertions.assertTrue("reset: \"Reset\" → \"Réinitialiser\"" in context, context)
        Assertions.assertTrue("\"Save all\" → \"Tout enregistrer\"" in context, "shares the word save: $context")
        Assertions.assertFalse("Quitter" in context, context)
        Assertions.assertFalse("ancienne" in context, "a value being retranslated is no example: $context")
    }

    @Test
    fun `a module is cut into calls of at most fifty keys`() {
        val items = (1..120).map { MachineFill.Item("common:k%03d".format(it), "text $it") }
        val proposals = MachineFill.translate(items, engine { Translation.Done("FR $it") }, "en", "fr", EmptyProgressIndicator())!!

        Assertions.assertEquals(listOf(50, 50, 20), requests.map { it.texts.size }.sortedDescending())
        Assertions.assertEquals(items.map { "FR ${it.source}" }, proposals.map { (it.result as Translation.Done).text }, "each key keeps its own translation")
    }

    @Test
    fun `a pass stops sending after two calls without a translation`() {
        val items = (1..500).map { MachineFill.Item("common:k%03d".format(it), "text $it") }
        val down = "Ollama did not answer in time"

        val proposals = MachineFill.translate(items, engine { Translation.Failed(down) }, "en", "fr", EmptyProgressIndicator())!!

        // Holds whatever order the calls end in. The bounded executor starts the calls in order, one more each
        // time a running one ends, and a call records its failure before it frees its slot: call number
        // PARALLEL_REQUESTS + n starts only once n calls have ended. With every call failing nothing resets the
        // count, so call PARALLEL_REQUESTS + FAILED_CALLS_BEFORE_STOP and every later one find the pass stopped.
        val bound = MachineFill.PARALLEL_REQUESTS + MachineFill.FAILED_CALLS_BEFORE_STOP - 1
        Assertions.assertTrue(requests.size <= bound, "${requests.size} calls of 10 sent, at most $bound")
        val stopped = Translation.Failed(PluginBundle.message("action.fill.stopped", down))
        Assertions.assertEquals(500 - requests.size * MachineFill.KEYS_PER_CALL, proposals.count { it.result == stopped }, "the rest names the engine")
        Assertions.assertTrue(proposals.all { it.result == stopped || it.result == Translation.Failed(down) })
    }

    @Test
    fun `the translations obtained before the stop are kept`() {
        val items = (1..500).map { MachineFill.Item("common:k%03d".format(it), "text $it") }
        // Only the first call's texts (1 to 50) are answered. The executor starts the first calls in order, but
        // their threads may run in any order: two failures recorded before the first call reaches the engine
        // would stop it unsent. So a failing call waits until the first one is in the engine. How many of the
        // others are sent is left out on purpose: the answered call may end after two failures and start the
        // count again, so it depends on the order the parallel calls end in.
        val firstSent = CountDownLatch(1)
        val proposals = MachineFill.translate(
            items,
            engine {
                if (it.removePrefix("text ").toInt() <= 50) {
                    firstSent.countDown()
                    Translation.Done("FR $it")
                } else {
                    firstSent.await(10, TimeUnit.SECONDS)
                    Translation.Failed("down")
                }
            },
            "en", "fr", EmptyProgressIndicator()
        )!!

        Assertions.assertEquals(items.take(50).map { "FR ${it.source}" }, proposals.take(50).map { (it.result as Translation.Done).text })
        val stopped = Translation.Failed(PluginBundle.message("action.fill.stopped", "down"))
        Assertions.assertTrue(
            proposals.drop(50).all { it.result == Translation.Failed("down") || it.result == stopped },
            "every other key failed, sent or stopped"
        )
    }

    @Test
    fun `a skipped item stays out of the call and keeps its place`() {
        val items = listOf(
            MachineFill.Item("common:a", "A"),
            MachineFill.Item("common:count", "{n, plural, one {# file} other {# files}}", skipped = icu),
            MachineFill.Item("common:b", "B"),
        )
        val proposals = MachineFill.translate(items, engine { Translation.Done("FR $it") }, "en", "fr", EmptyProgressIndicator())!!

        Assertions.assertEquals(listOf(Translation.Done("FR A"), Translation.Failed(icu), Translation.Done("FR B")), proposals.map { it.result })
        Assertions.assertEquals(listOf("A", "B"), requests.single().texts)
    }

    @Test
    fun `a cancelled run proposes nothing`() {
        val indicator = EmptyProgressIndicator().apply { cancel() }

        Assertions.assertNull(MachineFill.translate(MachineFill.itemsOf(translations, "en", "fr", "."), engine { Translation.Done("x") }, "en", "fr", indicator))
    }

    @Test
    fun `a fill cancelled while the engine translates writes nothing and opens no preview`() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"save": "Save"}""")
        val fr = addFileToProject("locales/fr/common.json", "{}")
        val sent = CountDownLatch(1)
        // An engine that answers only once cancelled, checking for it as a real one does between its waits.
        val slow = object : TranslationProvider {
            override fun translate(request: TranslationRequest, cancellation: Cancellation?): List<Translation> {
                sent.countDown()
                while (true) {
                    cancellation?.check()
                    if (Thread.currentThread().isInterrupted) return request.texts.map { Translation.Done("trop tard") }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
                }
            }
        }

        val job = MachineFill.run(project, null, "en", "fr", slow)
        PlatformTestUtil.waitWithEventsDispatching("the engine was never called", { sent.count == 0L }, 30)
        job.cancel()
        PlatformTestUtil.waitWithEventsDispatching("the cancelled fill never ended", { job.isCompleted }, 30)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        Assertions.assertTrue(job.isCancelled, "the job ends cancelled")
        Assertions.assertEquals("{}", FileDocumentManager.getInstance().getDocument(fr.virtualFile)!!.text, "nothing written")
    }

    @Test
    fun `an ICU plural goes with the target's branches, those copied from other to review`() {
        val items = MachineFill.itemsOf(mapOf("common:count" to mapOf("en" to "{n, plural, one {# file} other {# files}}")), "en", "ru", ".")
        val item = items.single()

        Assertions.assertEquals("{n, plural, one {# file} few {# files} many {# files} other {# files}}", item.sent)
        Assertions.assertEquals("{n, plural, one {# file} other {# files}}", item.source, "the text a review records")
        Assertions.assertTrue(item.needsReview)
        Assertions.assertEquals(PluginBundle.message("action.fill.icu.review", "few, many"), item.reviewNote)

        val proposals = MachineFill.translate(items, engine { Translation.Done("[ru] $it") }, "en", "ru", EmptyProgressIndicator())!!

        Assertions.assertEquals(listOf(item.sent), requests.single().texts, "sent with Russian's four branches")
        Assertions.assertEquals(Translation.Done("[ru] ${item.sent}"), proposals.single().result)
    }

    @Test
    fun `an ICU plural with the target's own branches goes as it is, nothing to review`() {
        val item = MachineFill.itemOf("common:count", "{n, plural, one {# file} other {# files}}", "de")

        Assertions.assertEquals(MachineFill.Item("common:count", "{n, plural, one {# file} other {# files}}"), item)
    }

    @Test
    fun `an ICU plural that cannot be rebuilt is never sent and says why`() {
        val item = MachineFill.itemOf("common:count", "{n, plural, one {# file}}", "fr")
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

    private val referenceAuth = """{"login": {"title": "Sign in", "form": "Form", "button": "Go"}, "errors": {"required": "Required"}}"""

    private val filled = mapOf(
        "auth:errors.required" to "Obbligatorio", "auth:login.button" to "Vai", "auth:login.form" to "Modulo", "auth:login.title" to "Accedi",
    )

    /** The order of the keys' first appearance in [text], nested ones included. */
    private fun keyOrder(text: String, vararg keys: String): List<String> =
        keys.sortedBy { text.indexOf("\"$it\"").also { index -> Assertions.assertTrue(index >= 0, "$it in $text") } }

    @Test
    fun `an empty file is filled in the reference locale's key order, nested keys included`() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/auth.json", referenceAuth)
        val italian = addFileToProject("locales/it/auth.json", "{}")

        MachineFill.write(project, null, "it", filled)

        val text = FileDocumentManager.getInstance().getDocument(italian.virtualFile)!!.text
        Assertions.assertEquals(listOf("login", "title", "form", "button", "errors", "required"),
            keyOrder(text, "errors", "login", "button", "form", "title", "required"), text)
    }

    @Test
    fun `a project sorting its keys alphabetically keeps them sorted`() = myFixture.runWithConfig(config.copy(sortKeysAlphabetically = true)) {
        addFileToProject("locales/en/auth.json", referenceAuth)
        val italian = addFileToProject("locales/it/auth.json", "{}")

        MachineFill.write(project, null, "it", filled)

        val text = FileDocumentManager.getInstance().getDocument(italian.virtualFile)!!.text
        Assertions.assertEquals(listOf("errors", "required", "login", "button", "form", "title"),
            keyOrder(text, "login", "title", "form", "button", "errors", "required"), text)
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

    @Test
    fun `three locales share one preview, told apart by a locale column`() = myFixture.runWithConfig(config) {
        val items = MachineFill.itemsOf(mapOf("common:save" to mapOf("en" to "Save")), "en", "de", ".")
        val proposals = listOf("de", "fr", "ja").flatMap { locale ->
            MachineFill.translate(items, engine { Translation.Done("[$locale] $it") }, "en", locale, EmptyProgressIndicator())!!
        }
        val dialog = MachineTranslationPreviewDialog(project, "de, fr, ja", proposals)
        try {
            Assertions.assertEquals(PluginBundle.message("action.fill.column.locale"), dialog.model.getColumnName(2))
            Assertions.assertEquals(listOf("de", "fr", "ja"), (0..2).map { dialog.model.getValueAt(it, 2) })
            Assertions.assertEquals(
                mapOf("de" to mapOf("common:save" to "[de] Save"), "fr" to mapOf("common:save" to "[fr] Save"), "ja" to mapOf("common:save" to "[ja] Save")),
                dialog.acceptedByLocale()
            )
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    @Test
    fun `a single locale keeps the preview without a locale column`() = myFixture.runWithConfig(config) {
        val proposals = listOf(MachineFill.Proposal(MachineFill.Item("common:save", "Save"), Translation.Done("Enregistrer"), "fr"))
        val dialog = MachineTranslationPreviewDialog(project, "fr", proposals)
        try {
            Assertions.assertEquals(6, dialog.model.columnCount)
            Assertions.assertEquals(mapOf("common:save" to "Enregistrer"), dialog.accepted())
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    @Test
    fun `a retranslation shows the value it replaces, and always marks what it writes where reviews are on`() = myFixture.runWithConfig(config) {
        val root = reviewRoot()
        WriteCommandAction.runWriteCommandAction(project) { store().enable(root) }
        val proposals = listOf(
            MachineFill.Proposal(MachineFill.Item("common:save", "Save all", current = "Enregistrer"), Translation.Done("Tout enregistrer"), "fr")
        )
        val dialog = MachineTranslationPreviewDialog(project, "fr", proposals, listOf(root))
        try {
            Assertions.assertEquals(7, dialog.model.columnCount)
            Assertions.assertEquals(PluginBundle.message("action.retranslate.column.current"), dialog.model.getColumnName(3))
            Assertions.assertEquals("Enregistrer", dialog.model.getValueAt(0, 3))
            Assertions.assertEquals("Tout enregistrer", dialog.model.getValueAt(0, 4))
            Assertions.assertTrue(dialog.markForReview.isSelected && !dialog.markForReview.isEnabled, "checked and locked")
            Assertions.assertEquals(mapOf("fr" to mapOf("common:save" to "machine:unknown")), dialog.reviewOrigins())
        } finally {
            Disposer.dispose(dialog.disposable)
        }
        Assertions.assertEquals(mapOf("fr" to mapOf("common:save" to "Enregistrer")), MachineFill.replacedOf(proposals))
    }

    /** The lagging locales of a module without reviews: nothing records a source there, nothing to lock. */
    @Test
    fun `a retranslation where reviews are off leaves the box unchecked and free`() = myFixture.runWithConfig(config) {
        val proposals = listOf(
            MachineFill.Proposal(MachineFill.Item("common:save", "Save all", current = "Enregistrer"), Translation.Done("Tout enregistrer"), "fr")
        )
        val dialog = MachineTranslationPreviewDialog(project, "fr", proposals, listOf(reviewRoot()))
        try {
            Assertions.assertTrue(dialog.markForReview.isVisible)
            Assertions.assertFalse(dialog.markForReview.isSelected, "not checked: nothing is marked by default")
            Assertions.assertTrue(dialog.markForReview.isEnabled, "not locked: checking it turns reviews on, as for a fill")
            Assertions.assertNull(dialog.reviewOrigins())
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    @Test
    fun `a replacing write replaces only the values still as previewed`() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"title": "Title", "save": "Save all", "cancel": "Cancel"}""")
        val fr = addFileToProject("locales/fr/common.json", """{"title": "Titre", "save": "Enregistrer", "cancel": "Annuler !"}""")

        MachineFill.write(
            project, null,
            mapOf("fr" to mapOf("common:save" to "Tout enregistrer", "common:cancel" to "Annuler", "common:title" to "Intitulé")),
            replaced = mapOf("fr" to mapOf("common:save" to "Enregistrer", "common:cancel" to "Annuler"))
        )

        val written = FileDocumentManager.getInstance().getDocument(fr.virtualFile)!!.text
        Assertions.assertTrue("\"Tout enregistrer\"" in written, written)
        Assertions.assertTrue("\"Annuler !\"" in written, "edited since the preview: left as it is: $written")
        Assertions.assertTrue("\"Titre\"" in written, "not a replaced key: an existing value is still never replaced: $written")
    }

    @Test
    fun `three locales are written in one command, undone by one Ctrl+Z`() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"save": "Save"}""")
        val files = listOf("de", "fr", "ja").associateWith { addFileToProject("locales/$it/common.json", "{}") }
        myFixture.openFileInEditor(files.getValue("fr").virtualFile)

        MachineFill.write(project, null, mapOf(
            "de" to mapOf("common:save" to "Speichern"),
            "fr" to mapOf("common:save" to "Enregistrer"),
            "ja" to mapOf("common:save" to "保存"),
        ))

        val documents = files.mapValues { FileDocumentManager.getInstance().getDocument(it.value.virtualFile)!! }
        Assertions.assertTrue("Speichern" in documents.getValue("de").text, documents.getValue("de").text)
        Assertions.assertTrue("Enregistrer" in documents.getValue("fr").text, documents.getValue("fr").text)
        Assertions.assertTrue("保存" in documents.getValue("ja").text, documents.getValue("ja").text)

        UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor!!)
        documents.forEach { (locale, document) ->
            Assertions.assertTrue("save" !in document.text, "one Ctrl+Z undoes $locale too: ${document.text}")
        }
    }

    /** The review store, signing and dating as a test can check. */
    private fun store(): ReviewStore = ReviewStore.getInstance(project).apply {
        now = { Instant.parse("2026-10-11T14:02:11Z") }
        reviewer = { "Alice <alice@example.com>" }
    }

    /** A module root of the fixture, standing for the one [ReviewLocation.rootOf] resolves. */
    private fun reviewRoot(): VirtualFile = myFixture.tempDirFixture.findOrCreateDir("app")

    private fun deepL(locale: String, vararg keys: String) = locale to keys.associateWith { Review.machineOrigin("DeepL") }

    @Test
    fun `checked, the values written are marked to review in the same command, undone with them`() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"title": "Title", "save": "Save", "cancel": "Cancel"}""")
        val fr = addFileToProject("locales/fr/common.json", """{"title": "Titre", "save": ""}""")
        myFixture.openFileInEditor(fr.virtualFile)
        val root = reviewRoot()
        val store = store()

        MachineFill.write(
            project, null,
            mapOf("fr" to mapOf("common:save" to "Enregistrer", "common:cancel" to "Annuler", "common:title" to "Intitulé")),
            MachineFill.ReviewMarks(root, mapOf(deepL("fr", "common:save", "common:cancel", "common:title")))
        )

        Assertions.assertTrue(store.isEnabled(root), "checking the box turns reviews on")
        val reviews = store.reviews(root, "fr")
        Assertions.assertEquals(
            setOf(ReviewKey("common", "save"), ReviewKey("common", "cancel")), reviews.keys,
            "a value already there is not rewritten, so not marked"
        )
        val save = reviews.getValue(ReviewKey("common", "save"))
        Assertions.assertEquals(ReviewState.TO_REVIEW, save.state)
        Assertions.assertEquals(ReviewHash.of("Enregistrer"), save.hash)
        Assertions.assertEquals("machine:DeepL", save.origin)
        Assertions.assertEquals("Alice <alice@example.com>", save.by)

        UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor!!)

        Assertions.assertFalse("Enregistrer" in myFixture.editor.document.text, myFixture.editor.document.text)
        Assertions.assertEquals(emptyMap<ReviewKey, Review>(), store.reviews(root, "fr"), "one Ctrl+Z undoes the marks too")
    }

    @Test
    fun `unchecked, nothing is created for reviews`() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"save": "Save"}""")
        val fr = addFileToProject("locales/fr/common.json", "{}")
        val root = reviewRoot()

        MachineFill.write(project, null, mapOf("fr" to mapOf("common:save" to "Enregistrer")), null)

        Assertions.assertTrue("Enregistrer" in FileDocumentManager.getInstance().getDocument(fr.virtualFile)!!.text)
        Assertions.assertNull(root.findChild(ReviewLocation.DIRECTORY))
    }

    @Test
    fun `several locales in one preview leave one review file per locale`() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"save": "Save"}""")
        listOf("de", "fr").forEach { addFileToProject("locales/$it/common.json", "{}") }
        val root = reviewRoot()
        val store = store()

        MachineFill.write(
            project, null,
            mapOf("de" to mapOf("common:save" to "Speichern"), "fr" to mapOf("common:save" to "Enregistrer")),
            MachineFill.ReviewMarks(root, mapOf(deepL("de", "common:save"), deepL("fr", "common:save")))
        )

        Assertions.assertEquals(setOf("de.json", "fr.json"), root.findChild(ReviewLocation.DIRECTORY)!!.children.map { it.name }.toSet())
        Assertions.assertEquals(ReviewHash.of("Speichern"), store.reviews(root, "de").getValue(ReviewKey("common", "save")).hash)
        Assertions.assertEquals(ReviewHash.of("Enregistrer"), store.reviews(root, "fr").getValue(ReviewKey("common", "save")).hash)
    }

    @Test
    fun `the box is checked when the module reviews already, and gives each value its engine`() = myFixture.runWithConfig(config) {
        val root = reviewRoot()
        val proposals = listOf(
            MachineFill.Proposal(MachineFill.Item("common:save", "Save"), Translation.Done("Enregistrer", TranslatedBy("DeepL", false)), "fr"),
            MachineFill.Proposal(MachineFill.Item("common:cancel", "Cancel"), Translation.Done("Annuler", TranslatedBy("Ollama", true)), "fr"),
            MachineFill.Proposal(MachineFill.Item("common:quit", "Quit"), Translation.Failed("quota"), "fr"),
        )
        fun <T> withDialog(block: (MachineTranslationPreviewDialog) -> T): T {
            val dialog = MachineTranslationPreviewDialog(project, "fr", proposals, listOf(root))
            try {
                return block(dialog)
            } finally {
                Disposer.dispose(dialog.disposable)
            }
        }

        withDialog { dialog ->
            Assertions.assertTrue(dialog.markForReview.isVisible)
            Assertions.assertFalse(dialog.markForReview.isSelected, "reviews off in the module: unchecked")
            Assertions.assertNull(dialog.reviewOrigins())
            dialog.markForReview.isSelected = true
            Assertions.assertEquals(
                mapOf("fr" to mapOf("common:save" to "machine:DeepL", "common:cancel" to "machine:Ollama")),
                dialog.reviewOrigins()
            )
        }

        WriteCommandAction.runWriteCommandAction(project) { store().enable(root) }
        withDialog { dialog -> Assertions.assertTrue(dialog.markForReview.isSelected, "reviews on in the module: checked") }

        val noRoot = MachineTranslationPreviewDialog(project, "fr", proposals)
        try {
            Assertions.assertFalse(noRoot.markForReview.isVisible)
            Assertions.assertNull(noRoot.reviewOrigins())
        } finally {
            Disposer.dispose(noRoot.disposable)
        }
    }

    @Test
    fun `each proposal's source is the text sent, the other form's for a form from other, none for a skipped item`() {
        val proposals = MachineFill.translate(MachineFill.itemsOf(plural, "en", "ru", "."), engine { Translation.Done("[ru] $it") }, "en", "ru", EmptyProgressIndicator())!! +
            MachineFill.Proposal(MachineFill.Item("common:count", "{n, plural, one {# file} other {# files}}", skipped = icu), Translation.Failed(icu), "ru")

        Assertions.assertEquals(
            mapOf("ru" to mapOf(
                "files:file_few" to ReviewSource.of("en", "{{count}} files"),
                "files:file_many" to ReviewSource.of("en", "{{count}} files"),
                "files:file_one" to ReviewSource.of("en", "{{count}} file"),
                "files:file_other" to ReviewSource.of("en", "{{count}} files"),
            )),
            MachineFill.reviewSourcesOf(proposals, "en")
        )
    }

    @Test
    fun `a value marked to review keeps its source, and shows when that source changes`() = myFixture.runWithConfig(config) {
        val en = addFileToProject("locales/en/common.json", """{"save": "Save", "file_one": "{{count}} file", "file_other": "{{count}} files"}""")
        addFileToProject("locales/ru/common.json", "{}")
        val root = reviewRoot()
        val store = store()
        val items = MachineFill.itemsOf(TranslationDataLoader.loadAllTranslations(project, null), "en", "ru", ".")
        val proposals = MachineFill.translate(items, engine { Translation.Done("[ru] $it", TranslatedBy("DeepL", false)) }, "en", "ru", EmptyProgressIndicator())!!
        val accepted = proposals.associate { it.item.key to (it.result as Translation.Done).text }

        MachineFill.write(
            project, null, mapOf("ru" to accepted),
            MachineFill.ReviewMarks(root, mapOf(deepL("ru", *accepted.keys.toTypedArray())), MachineFill.reviewSourcesOf(proposals, "en"))
        )

        val reviews = store.reviews(root, "ru")
        val save = reviews.getValue(ReviewKey("common", "save"))
        Assertions.assertEquals(ReviewSource.of("en", "Save"), save.source)
        Assertions.assertEquals(ReviewSource.of("en", "{{count}} file"), reviews.getValue(ReviewKey("common", "file_one")).source)
        val few = reviews.getValue(ReviewKey("common", "file_few"))
        Assertions.assertEquals(ReviewSource.of("en", "{{count}} files"), few.source, "a form translated from other records other's source")

        // The source as the table and the inspection read it: the key's own, or its group's `other`.
        fun currentSource(key: String): ReviewSource? {
            val translations = TranslationDataLoader.loadAllTranslations(project, null)
            return ReviewSources.sourceTextOf(key, config.pluralSeparator) { translations[it]?.get("en") }?.let { ReviewSource.of("en", it) }
        }
        Assertions.assertEquals(ShownReview.TO_REVIEW, shown(save, "[ru] Save", currentSource("common:save")))
        Assertions.assertEquals(ShownReview.TO_REVIEW, shown(few, "[ru] {{count}} files", currentSource("common:file_few")))

        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(en)!!
            document.setText(document.text.replace("\"Save\"", "\"Save all\"").replace("\"{{count}} files\"", "\"{{count}} files in all\""))
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        Assertions.assertEquals(ShownReview.SOURCE_CHANGED, shown(save, "[ru] Save", currentSource("common:save")))
        Assertions.assertEquals(
            ShownReview.SOURCE_CHANGED, shown(few, "[ru] {{count}} files", currentSource("common:file_few")),
            "English `other` changed: the Russian `few` translated from it is stale"
        )
        Assertions.assertEquals(
            ShownReview.TO_REVIEW, shown(few.copy(source = null), "[ru] {{count}} files", currentSource("common:file_few")),
            "a review written before, without source, never turns stale"
        )
    }

    // ---- project memory ----

    private val memoryKnown = mapOf(
        "common:actions.save" to mapOf("en" to "Save", "fr" to "Enregistrer"),
        "common:greeting" to mapOf("en" to "Hello {{name}}", "fr" to "Bonjour {{name}}"),
    )

    @Test
    fun `a text the module already translates is filled from it, the engine never called`() {
        val items = listOf(MachineFill.Item("cart:save", "Save"), MachineFill.Item("cart:hello", "Hello {{name}}"))
        val known = memoryKnown + items.associate { it.key to mapOf("en" to it.source) }

        val proposals = MachineFill.translate(items, engine { Translation.Done("x") }, "en", "fr", EmptyProgressIndicator(), known, config)!!

        Assertions.assertTrue(requests.isEmpty(), "nothing goes to the engine: ${requests.map { it.texts }}")
        Assertions.assertEquals(listOf(Translation.Done("Enregistrer"), Translation.Done("Bonjour {{name}}")), proposals.map { it.result })
        Assertions.assertEquals(listOf(true, true), proposals.map { it.memory?.unanimous })
        Assertions.assertEquals(MachineFill.MEMORY_ENGINE, (proposals.first().result as Translation.Done).engine)
    }

    @Test
    fun `only the texts the memory lacks go to the engine, the remembered keys left out of the context`() {
        val items = listOf(MachineFill.Item("cart:quit", "Quit"), MachineFill.Item("cart:save", "Save"))

        val proposals = MachineFill.translate(items, engine { Translation.Done("FR $it") }, "en", "fr", EmptyProgressIndicator(), memoryKnown, config)!!

        Assertions.assertEquals(listOf(Translation.Done("FR Quit"), Translation.Done("Enregistrer")), proposals.map { it.result })
        val request = requests.single()
        Assertions.assertEquals(listOf("Quit"), request.texts)
        Assertions.assertFalse("cart:save" in request.context, request.context)
    }

    @Test
    fun `a plural form translated from other, or a value with other placeholders, still goes to the engine`() {
        val known = mapOf(
            "common:files_other" to mapOf("en" to "{{count}} files", "ru" to "{{count}} файлов"),
            "common:hello" to mapOf("en" to "Hello {{name}}", "ru" to "Привет"),
        )
        val items = listOf(
            MachineFill.Item("cart:items_few", "{{count}} files", needsReview = true),
            MachineFill.Item("cart:hello", "Hello {{name}}"),
        )

        MachineFill.translate(items, engine { Translation.Done("x") }, "en", "ru", EmptyProgressIndicator(), known, config)!!

        Assertions.assertEquals(listOf("{{count}} files", "Hello {{name}}"), requests.single().texts)
    }

    @Test
    fun `the preview names the project memory, a divergent value unchecked with its note`() = myFixture.runWithConfig(config) {
        val known = mapOf(
            "a:save" to mapOf("en" to "Save", "fr" to "Sauvegarder"),
            "b:save" to mapOf("en" to "Save", "fr" to "Enregistrer"),
            "c:save" to mapOf("en" to "Save", "fr" to "Enregistrer"),
            "d:title" to mapOf("en" to "Title", "fr" to "Titre"),
        )
        val items = listOf(MachineFill.Item("cart:save", "Save"), MachineFill.Item("cart:title", "Title"))
        val proposals = MachineFill.translate(items, engine { Translation.Done("x") }, "en", "fr", EmptyProgressIndicator(), known, config)!!
        val dialog = MachineTranslationPreviewDialog(project, "fr", proposals)
        try {
            Assertions.assertEquals(listOf("Enregistrer", "Titre"), (0..1).map { dialog.model.getValueAt(it, 3) })
            Assertions.assertEquals(false, dialog.model.getValueAt(0, 0), "translated two ways in the project: the user decides")
            Assertions.assertEquals(PluginBundle.message("action.fill.memory.divergent"), dialog.model.getValueAt(0, 4))
            Assertions.assertEquals(true, dialog.model.getValueAt(1, 0), "translated one way: checked")
            Assertions.assertEquals(listOf(PluginBundle.message("action.fill.engine.memory")), (0..1).map { dialog.model.getValueAt(it, 5) }.distinct())
            Assertions.assertTrue(dialog.model.isCellEditable(0, 0), "a divergent value can still be checked")
            Assertions.assertEquals(mapOf("cart:title" to "Titre"), dialog.accepted())
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    @Test
    fun `a module never reads the memory of another module`() = myFixture.runWithConfig(config) {
        addFileToProject("web/locales/en/common.json", """{"save": "Save"}""")
        addFileToProject("web/locales/fr/common.json", "{}")
        addFileToProject("admin/locales/en/common.json", """{"actions": {"save": "Save"}}""")
        addFileToProject("admin/locales/fr/common.json", """{"actions": {"save": "Sauvegarder"}}""")
        // In this light fixture the project files live under `src/` (see TableViewModelSaveValueTest).
        val web = ModuleConfig(name = "web", rootDirectory = "src/web")

        fun fill(module: ModuleConfig?): List<MachineFill.Proposal> {
            val known = TranslationDataLoader.loadAllTranslations(project, module)
            val items = MachineFill.itemsOf(known, "en", "fr", ".").filter { it.key == "common:save" }
            return MachineFill.translate(items, engine { Translation.Done("FR $it") }, "en", "fr", EmptyProgressIndicator(), known, config)!!
        }

        Assertions.assertEquals(Translation.Done("Sauvegarder"), fill(null).single().result, "without modules, the admin value is the project's")
        requests.clear()
        val scoped = fill(web).single()
        Assertions.assertEquals(Translation.Done("FR Save"), scoped.result, "the web module asks the engine")
        Assertions.assertNull(scoped.memory)
        Assertions.assertEquals(listOf("Save"), requests.single().texts)
    }
}
