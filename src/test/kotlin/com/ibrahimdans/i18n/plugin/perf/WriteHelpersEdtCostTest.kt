package com.ibrahimdans.i18n.plugin.perf

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.CleanupUnusedKeysAction
import com.ibrahimdans.i18n.plugin.ide.actions.MachineFill
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.review.ReviewMigration
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.ibrahimdans.i18n.plugin.utils.sourcesOf
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * How long [MachineFill.write] and [CleanupUnusedKeysAction.deleteKeys] hold the EDT on a project of
 * [LargeProjectFixture]'s size, for [KEYS] keys: the measure TASK-WRITE-HELPERS-OFF-EDT was to start
 * from, with its threshold — [THRESHOLD_MS] — written before the figures.
 *
 * Each helper is timed whole (the slot it takes on the EDT) and for its reads alone (what a plan read
 * off the EDT would move away): the module's sources, its translations and the reference order for a
 * fill; the sources, the properties to delete and their reviews for a cleanup. "Warm": right after a
 * read of the same translations, as an MCP tool or a fill runs it once its plan or preview is done.
 * "After an edit": a translation file changed just before, so every cache has to be rebuilt.
 *
 * Figures are logged as `[edt-cost]` lines. Opt-in like [HighlightingPerformanceTest]:
 * `I18N_PERF=true ./gradlew :test --rerun --tests com.ibrahimdans.i18n.plugin.perf.WriteHelpersEdtCostTest`.
 */
@EnabledIfEnvironmentVariable(named = "I18N_PERF", matches = "true")
class WriteHelpersEdtCostTest : PlatformBaseTest() {

    override fun setUp() {
        super.setUp()
        LargeProjectFixture.translationFiles().forEach { (path, content) -> addFileToProject(path, content) }
    }

    @Test
    fun fillWriteSlot() {
        assertTrue(ApplicationManager.getApplication().isDispatchThread, "measured on the EDT, where the write runs")
        TranslationDataLoader.loadAllTranslations(project)

        val warmReads = medianOf { timed { fillReads() } }
        val warmWhole = medianOf { run -> timed { MachineFill.write(project, null, "fr", newKeys(run)) } }
        val editedReads = medianOf { touch(); timed { fillReads() } }
        val editedWhole = medianOf { run -> touch(); timed { MachineFill.write(project, null, "fr", newKeys(RUNS + run)) } }

        report("MachineFill.write", warmReads, warmWhole, editedReads, editedWhole)
    }

    @Test
    fun cleanupDeleteSlot() {
        assertTrue(ApplicationManager.getApplication().isDispatchThread, "measured on the EDT, where the deletion runs")
        TranslationDataLoader.loadAllTranslations(project)
        val action = CleanupUnusedKeysAction()

        val warmReads = medianOf { run -> timed { cleanupReads(action, existingKeys(run)) } }
        val warmWhole = medianOf { run -> timed { action.deleteKeys(project, existingKeys(run)) } }
        val editedReads = medianOf { run -> touch(); timed { cleanupReads(action, existingKeys(RUNS + run)) } }
        val editedWhole = medianOf { run -> touch(); timed { action.deleteKeys(project, existingKeys(RUNS + run)) } }

        report("CleanupUnusedKeysAction.deleteKeys", warmReads, warmWhole, editedReads, editedWhole)
    }

    /** The reads [MachineFill.write] makes before its command, as it makes them. */
    private fun fillReads() {
        val config = Settings.getInstance(project).config()
        val sources = ReadAction.compute<List<LocalizationSource>, RuntimeException> { TranslationDataLoader.findSources(project, null) }
        TranslationDataLoader.loadAllTranslations(project, null)
        val locales = sources.map(TranslationDataLoader::extractLocale).distinct().sorted()
        val reference = ReferenceLocale.of(null, config, locales) ?: locales.first()
        TranslationDataLoader.translationsOf(project, sources.filter { TranslationDataLoader.extractLocale(it) == reference })
    }

    /** The reads [CleanupUnusedKeysAction.deleteKeys] makes before its command, as it makes them. */
    private fun cleanupReads(action: CleanupUnusedKeysAction, keys: List<String>) {
        ReadAction.run<RuntimeException> {
            val sources = ModuleSources.sourcesOf(project, null)
            val config = Settings.getInstance(project).config()
            val found = keys.flatMap { action.leafProperties(sources, it, config) }
            ReviewMigration.spotsOf(found)
        }
    }

    /** [KEYS] keys none of the files holds yet, a namespace per run so that no run writes another's. */
    private fun newKeys(run: Int): Map<String, String> =
        (0 until KEYS).associate { "${LargeProjectFixture.namespace(1 + run)}:group0.extra$it" to "valeur $it" }

    /** [KEYS] keys every locale holds, from a namespace no other run deletes from. */
    private fun existingKeys(run: Int): List<String> {
        val namespace = LargeProjectFixture.namespace(1 + run)
        return (0 until LargeProjectFixture.GROUPS).flatMap { group ->
            (0 until LargeProjectFixture.KEYS_PER_GROUP).map { "$namespace:group$group.key$it" }
        }.take(KEYS)
    }

    /** A translation file edited and committed: the PSI modification count moves, the caches go stale. */
    private fun touch() {
        val file = myFixture.findFileInTempDir("locales/en/${LargeProjectFixture.namespace(0)}.json")
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, " ") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun medianOf(measure: (Int) -> Long): Long = List(RUNS) { measure(it) }.sorted()[RUNS / 2]

    private fun report(helper: String, warmReads: Long, warmWhole: Long, editedReads: Long, editedWhole: Long) {
        val files = LargeProjectFixture.LOCALES.size * LargeProjectFixture.NAMESPACES
        println("[edt-cost] $helper, $KEYS keys, $files files, median of $RUNS — threshold $THRESHOLD_MS ms")
        println("[edt-cost]   warm:          reads $warmReads ms, whole call $warmWhole ms")
        println("[edt-cost]   after an edit: reads $editedReads ms, whole call $editedWhole ms")
    }

    private companion object {
        const val KEYS = 20
        const val RUNS = 5

        /** Above it for either helper, its reads move off the EDT; written before the first figure. */
        const val THRESHOLD_MS = 100L
    }
}
