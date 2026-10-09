package com.ibrahimdans.i18n.plugin.perf

import com.ibrahimdans.i18n.extensions.lang.js.InterpolationArgumentsInspection
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.codevision.TranslationUsagesCodeVisionProvider
import com.ibrahimdans.i18n.plugin.ide.inspection.IcuFormatInspection
import com.ibrahimdans.i18n.plugin.ide.inspection.MarkupConsistencyInspection
import com.ibrahimdans.i18n.plugin.ide.inspection.MissingPluralFormsInspection
import com.ibrahimdans.i18n.plugin.ide.inspection.MissingTranslationKeyInspection
import com.ibrahimdans.i18n.plugin.ide.inspection.PlaceholderConsistencyInspection
import com.ibrahimdans.i18n.plugin.ide.inspection.UntranslatedValueInspection
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * What one highlighting pass costs on a project of [LargeProjectFixture]'s size — the reference
 * every performance change is compared against.
 *
 * Times are logged (`[perf]` lines in the test's standard output) rather than asserted to the
 * millisecond: a CI machine is too noisy for that. Each scenario only has a ceiling far above the
 * measured cost, as a net against an order-of-magnitude regression.
 *
 * Opt-in: building the 500-file fixture for each scenario takes the class past a minute, too much
 * for every `./gradlew test`. Run it with
 * `I18N_PERF=true ./gradlew test --rerun --tests com.ibrahimdans.i18n.plugin.perf.HighlightingPerformanceTest`
 * (`--rerun`: the variable is no task input, so Gradle would otherwise replay the skipped run),
 * and read the figures in `build/test-results/test/TEST-*HighlightingPerformanceTest.xml`.
 *
 * Measured on 2026-10-09 (IU 2025.3.4, local machine, three runs), before any of the TASK-PERF-*
 * changes:
 *
 * | Scenario                                         | Time       |
 * |--------------------------------------------------|------------|
 * | (a) first highlighting of the component          | 5.2–8.2 s  |
 * | (b) highlighting after typing in the component   | 1.0–1.3 s  |
 * | (c) highlighting after typing in a Java file     | ~35 ms     |
 * | (d) `findAllSources` rescan after an edit        | 3–7 ms     |
 * | (e) one pass worth of `findNamespaceFiles` calls | 290–540 ms |
 *
 * (e) is a third to a half of (b), and (d) shows that the project-wide rescan itself is cheap.
 * A change that moves one of these figures updates this table.
 *
 * After TASK-PERF-NAMESPACE-CACHE (cached `findNamespaceFiles`, exclusions built once per scan): (e)
 * 0 ms, since every call after the first is a cache hit. (b) did not move beyond the noise: each
 * keystroke still drops the cache, and a JFR profile of (b) puts nearly half of the plugin's time
 * in `JsLang.canExtractKey`, run on every PSI element by the annotator, inlays, gutter and references.
 *
 * After TASK-PERF-CAN-EXTRACT-KEY (tree walks hoisted out of the per-name loop): `canExtractKey` fell
 * from 133 to 45 samples in (b)'s window, out of the top of the profile; (b) 0.8–0.9 s on a loaded
 * machine. The plugin's remaining cost in (b) is the per-namespace lookups the keystroke invalidated
 * and `findAllSources`, which the language-less inspections reach on the `.tsx` itself.
 *
 * After TASK-PERF-INSPECTION-SCOPE (`TranslationFileScope.sourceOf` rules out a file of no translation
 * kind before the scan): `findAllSources` left (b)'s profile; samples of the highlighting passes holding
 * a plugin frame fell from 172 to 93 (40 % to 29 % of the passes). (b) 0.8–1.4 s, still noisy. What the
 * plugin keeps in (b) is `findSources`, recomputed per namespace after each keystroke.
 *
 * After TASK-PERF-TRANSLATION-TRACKER (the file-index scan stamped with `TranslationModificationTracker`):
 * the same source instances are served across keystrokes, and no file-index scan is left in the
 * profile of (b)'s measured loop. Plugin frames in its highlighting passes: 38 % before, 28 % after;
 * what remains is the inlay collector, `canExtractKey` and the `useTranslation` hook resolution. (b)
 * itself, 0.8–0.9 s, is now mostly the platform's own JS highlighting. Profiles taken from the
 * measured loop only (the `medianOf` lambda): the warm-up pass before it is cold by design.
 *
 * After TASK-PERF-INLAY-COLLECTOR (config and names read once per inlay collector, `PhpLang` testing
 * its extractor first): plugin frames in (b)'s passes 28 % → 16–18 % (two profiles, heavily loaded
 * machine — times not comparable). Left: `JsLang.canExtractKey` and the `useTranslation` hook
 * resolution, both under the inlay collector.
 *
 * TASK-PERF-CODEVISION added (f), the usages code vision on a JSON file after a keystroke in it. On a
 * 500-key file: 7.2 s per pass, 6.5 s for the same searches without the 99-usage cap — the cost is
 * one reference search per key, about 14 ms each, which the cap does not touch. `MAX_KEYS` lowered
 * from 500 to 100 to bound it; (f) now measures a file at that limit: 1.2 s.
 */
@EnabledIfEnvironmentVariable(named = "I18N_PERF", matches = "true")
class HighlightingPerformanceTest : PlatformBaseTest() {

    override fun setUp() {
        super.setUp()
        // The inspections enabled by default, as a user gets them: the language-less ones run on
        // every file of the IDE, which is what scenario (c) measures.
        myFixture.enableInspections(
            PlaceholderConsistencyInspection(),
            MissingPluralFormsInspection(),
            MarkupConsistencyInspection(),
            UntranslatedValueInspection(),
            IcuFormatInspection(),
            MissingTranslationKeyInspection(),
            InterpolationArgumentsInspection(),
        )
        LargeProjectFixture.translationFiles().forEach { (path, content) -> addFileToProject(path, content) }
    }

    /** (a) The component opened for the first time: every key resolved from cold caches. */
    @Test
    fun firstHighlightingOfTheComponent() {
        myFixture.configureByText("Dashboard.tsx", LargeProjectFixture.component())

        val elapsed = timed { myFixture.doHighlighting() }
        log("(a) first highlighting of a ${LargeProjectFixture.KEY_CALLS}-key component", elapsed)

        assertAllKeysResolved()
        assertBelow(FIRST_HIGHLIGHTING_CEILING_MS, elapsed)
    }

    /** (b) A character typed in the component, then the pass it triggers. */
    @Test
    fun highlightingAfterTypingInTheComponent() {
        myFixture.configureByText("Dashboard.tsx", LargeProjectFixture.component())
        myFixture.doHighlighting()
        assertAllKeysResolved()

        val elapsed = medianOf(RUNS) {
            myFixture.type("x")
            timed { myFixture.doHighlighting() }
        }
        log("(b) highlighting after typing in the component, median of $RUNS", elapsed)

        assertBelow(TYPING_CEILING_MS, elapsed)
    }

    /** (c) A character typed in a file the plugin has nothing to do with. */
    @Test
    fun highlightingAfterTypingInAnUnrelatedFile() {
        myFixture.configureByText("Notes.java", LargeProjectFixture.unrelatedJavaFile())
        myFixture.doHighlighting()

        val elapsed = medianOf(RUNS) {
            myFixture.type("x")
            timed { myFixture.doHighlighting() }
        }
        log("(c) highlighting after typing in an unrelated Java file, median of $RUNS", elapsed)

        assertBelow(UNRELATED_TYPING_CEILING_MS, elapsed)
    }

    /**
     * (d) The raw cost of [LocalizationSourceService.findAllSources] once a character typed anywhere
     * has moved the PSI modification count: the rescan every consumer pays first after an edit.
     */
    @Test
    fun rescanOfAllSourcesAfterAnEdit() {
        myFixture.configureByText("Notes.java", LargeProjectFixture.unrelatedJavaFile())
        val service = project.service<LocalizationSourceService>()
        assertEquals(LargeProjectFixture.LOCALES.size * LargeProjectFixture.NAMESPACES, service.findAllSources(project).size)

        val elapsed = medianOf(RUNS) {
            myFixture.type("x")
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            timed { service.findAllSources(project) }
        }
        log("(d) findAllSources rescan after an edit, median of $RUNS", elapsed)

        assertBelow(RESCAN_CEILING_MS, elapsed)
    }

    /**
     * (e) [LocalizationSourceService.findNamespaceFiles], which the annotator calls for every key
     * naming a namespace, as many times as one pass of the component asks it.
     */
    @Test
    fun namespaceFileLookupsOfOnePass() {
        val service = project.service<LocalizationSourceService>()
        val namespacedKeys = LargeProjectFixture.KEY_CALLS / 2
        assertEquals(LargeProjectFixture.LOCALES.size, service.findNamespaceFiles(listOf("ns1"), project).size)

        val elapsed = medianOf(RUNS) {
            timed { repeat(namespacedKeys) { i -> service.findNamespaceFiles(listOf(LargeProjectFixture.namespace(1 + i % (LargeProjectFixture.NAMESPACES - 1))), project) } }
        }
        log("(e) $namespacedKeys findNamespaceFiles calls (one pass), median of $RUNS", elapsed)

        assertBelow(NAMESPACE_LOOKUPS_CEILING_MS, elapsed)
    }

    /**
     * (f) What the usages code vision costs on a [LargeProjectFixture.CATALOG_KEYS]-key JSON file after
     * a character typed in it: one reference search per key, repaid after each edit.
     */
    @Test
    fun usagesCodeVisionAfterTypingInALargeJsonFile() {
        addFileToProject("src/Catalog.js", LargeProjectFixture.catalogComponent())
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("locales/en/catalog.json", LargeProjectFixture.catalog()).virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("value 0"))
        val provider = TranslationUsagesCodeVisionProvider()
        val labels = runReadAction { provider.computeForEditor(myFixture.editor, myFixture.file) }
        assertEquals(LargeProjectFixture.CATALOG_KEYS, labels.size)

        val elapsed = medianOf(RUNS) {
            myFixture.type("x")
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            timed { runReadAction { provider.computeForEditor(myFixture.editor, myFixture.file) } }
        }
        log("(f) usages code vision after typing in a ${LargeProjectFixture.CATALOG_KEYS}-key JSON file, median of $RUNS", elapsed)

        assertBelow(CODE_VISION_CEILING_MS, elapsed)
    }

    /** The benchmark is only worth something on the resolved path: a fixture mistake must fail here. */
    private fun assertAllKeysResolved() {
        val unresolved = myFixture.doHighlighting().filter { it.description?.contains("Unresolved") == true }
        assertEquals(emptyList<String>(), unresolved.map { "${it.description} at ${it.startOffset}" })
    }

    private fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun medianOf(runs: Int, measure: () -> Long): Long =
        List(runs) { measure() }.sorted()[runs / 2]

    private fun log(scenario: String, elapsedMs: Long) =
        println("[perf] $scenario: $elapsedMs ms (${LargeProjectFixture.LOCALES.size * LargeProjectFixture.NAMESPACES} translation files)")

    private fun assertBelow(ceilingMs: Long, elapsedMs: Long) =
        assertTrue(elapsedMs < ceilingMs, "took $elapsedMs ms, ceiling $ceilingMs ms")

    private companion object {
        const val RUNS = 5

        // About 10x the cost measured on 2026-10-09 (or since lowered by a TASK-PERF-* change), with a 1 s floor for the figures too small to
        // be stable. A change that makes a scenario much faster lowers its ceiling with it.
        const val FIRST_HIGHLIGHTING_CEILING_MS = 65_000L
        const val TYPING_CEILING_MS = 10_000L
        const val UNRELATED_TYPING_CEILING_MS = 1_000L
        const val RESCAN_CEILING_MS = 1_000L
        const val NAMESPACE_LOOKUPS_CEILING_MS = 1_000L
        const val CODE_VISION_CEILING_MS = 15_000L
    }
}
