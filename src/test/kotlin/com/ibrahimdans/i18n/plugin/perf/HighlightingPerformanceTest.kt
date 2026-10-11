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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * What one highlighting pass costs on a project of [LargeProjectFixture]'s size — the reference
 * every performance change is compared against.
 *
 * Times are logged (`[perf]` lines in the test's standard output) and written to
 * `build/perf/results.md`, one row per scenario: the table the weekly `.github/workflows/perf.yml`
 * run publishes as its summary. Each scenario fails above its ceiling (see "Ceilings" below), and
 * the failure message names the scenario.
 *
 * Opt-in: building the 500-file fixture for each scenario takes the class past a minute, too much
 * for every `./gradlew test`. Run it with
 * `I18N_PERF=true ./gradlew test --rerun --tests com.ibrahimdans.i18n.plugin.perf.HighlightingPerformanceTest`
 * (`--rerun`: the variable is no task input, so Gradle would otherwise replay the skipped run),
 * and read the figures in `build/perf/results.md`. `I18N_PERF_FACTOR=<x>` replaces the ceiling
 * factor for one run: `0.1` makes the slow scenarios fail on purpose, to check the net still bites.
 *
 * ### Ceilings
 *
 * Ceiling = max([CEILING_FACTOR] × reference, [CEILING_FLOOR_MS]). The reference is the upper end of
 * the calm-machine figures re-measured on 2026-10-11 (TASK-PERF-REMEASURE, local machine, 12 cores),
 * on the 2026-10-09 reference commit as on main — they live in [Scenario], the single source of the
 * thresholds (the workflow only publishes what this class writes):
 *
 * | Scenario | Re-measured 2026-10-11               | Reference | Ceiling |
 * |----------|--------------------------------------|-----------|---------|
 * | (a)      | 4.6–6.0 s                            | 6 000 ms  | 48 s    |
 * | (b)      | 0.82–0.88 s, 1.5 s under load        | 880 ms    | 7 s     |
 * | (c)      | 27–50 ms                             | 50 ms     | 400 ms  |
 * | (d)      | 0–3 ms                               | 3 ms      | 200 ms  |
 * | (e)      | 0–1 ms                               | 1 ms      | 200 ms  |
 * | (f)      | 1.6–1.7 s calm, 3.9–4.4 s under load | 1 700 ms  | 13.6 s  |
 *
 * Why ×8: load alone doubles the local figures on the same commit, and (f) more than that — 4.4 s
 * (×2.6) on 2026-10-11, then 8.9 s (×5.25) on the run that introduced these ceilings, load average 14
 * on 12 cores, (b) at 1.9 s (×2.1) in the same run. A first draft at ×5 failed (f) there for no
 * regression. A GitHub runner is slower than the reference machine and shares its host, so ×8 keeps
 * a 50 % margin over the worst figure seen, and the CI retry (one per failed test) asks a spike to
 * strike twice. It still catches what this net is for: a lost cache or a search per key that
 * multiplies a pass. A smaller drift stays below it — that one is read in the published table, and
 * once a few weekly runs show the runner's own spread, the factor can come down to match it.
 *
 * Why a floor: (d) and (e) cost a few milliseconds, where a ratio measures timer resolution and GC
 * pauses. 200 ms ignores that noise and still catches what those scenarios guard: (e) was 290–540 ms
 * before TASK-PERF-NAMESPACE-CACHE, and a (d) that reaches the file index again costs as much.
 *
 * ### History
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
 * A change that moves one of these figures updates the references in [Scenario] and the table above.
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

        assertAllKeysResolved()
        report(Scenario.FIRST_HIGHLIGHTING, elapsed)
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

        report(Scenario.TYPING, elapsed)
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

        report(Scenario.UNRELATED_TYPING, elapsed)
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

        report(Scenario.RESCAN, elapsed)
    }

    /**
     * (e) [LocalizationSourceService.findNamespaceFiles], which the annotator calls for every key
     * naming a namespace, as many times as one pass of the component asks it.
     */
    @Test
    fun namespaceFileLookupsOfOnePass() {
        val service = project.service<LocalizationSourceService>()
        assertEquals(LargeProjectFixture.LOCALES.size, service.findNamespaceFiles(listOf("ns1"), project).size)

        val elapsed = medianOf(RUNS) {
            timed { repeat(NAMESPACED_KEYS) { i -> service.findNamespaceFiles(listOf(LargeProjectFixture.namespace(1 + i % (LargeProjectFixture.NAMESPACES - 1))), project) } }
        }

        report(Scenario.NAMESPACE_LOOKUPS, elapsed)
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

        report(Scenario.CODE_VISION, elapsed)
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

    /**
     * Logs the time, records it in the results table, then holds it to the scenario's ceiling. The
     * row is written before the assertion so that a scenario over its ceiling still shows in the table.
     */
    private fun report(scenario: Scenario, elapsedMs: Long) {
        val factor = ceilingFactor()
        val ceilingMs = scenario.ceilingMs(factor)
        println("[perf] ${scenario.title}: $elapsedMs ms, ceiling $ceilingMs ms (${LargeProjectFixture.LOCALES.size * LargeProjectFixture.NAMESPACES} translation files)")
        Results.record(scenario, elapsedMs, ceilingMs, factor)
        assertTrue(
            elapsedMs < ceilingMs,
            "${scenario.title} took $elapsedMs ms, over its ceiling of $ceilingMs ms " +
                "(max(${factor.format()} × reference ${scenario.referenceMs} ms, floor $CEILING_FLOOR_MS ms))",
        )
    }

    /**
     * The six scenarios and their references: the only place the thresholds live. A change that moves a
     * scenario for good updates its reference here and in the class KDoc.
     */
    private enum class Scenario(val id: String, val label: String, val referenceMs: Long) {
        FIRST_HIGHLIGHTING("a", "first highlighting of a ${LargeProjectFixture.KEY_CALLS}-key component", 6_000),
        TYPING("b", "highlighting after typing in the component, median of $RUNS", 880),
        UNRELATED_TYPING("c", "highlighting after typing in an unrelated Java file, median of $RUNS", 50),
        RESCAN("d", "findAllSources rescan after an edit, median of $RUNS", 3),
        NAMESPACE_LOOKUPS("e", "$NAMESPACED_KEYS findNamespaceFiles calls (one pass), median of $RUNS", 1),
        CODE_VISION("f", "usages code vision after typing in a ${LargeProjectFixture.CATALOG_KEYS}-key JSON file, median of $RUNS", 1_700);

        val title get() = "($id) $label"

        fun ceilingMs(factor: Double): Long = max((referenceMs * factor).roundToLong(), CEILING_FLOOR_MS)
    }

    /**
     * `build/perf/results.md`, rebuilt after each scenario from one row file per scenario. Row files
     * rather than an in-memory table: the CI retry of a failed test runs in a new JVM, which would
     * otherwise publish the retried scenario alone. A scenario that never reached its measure (the
     * fixture failed first) shows as "not measured".
     */
    private object Results {
        private val dir: Path = Path.of("build", "perf")
        private val rows: Path = dir.resolve("rows")

        fun record(scenario: Scenario, elapsedMs: Long, ceilingMs: Long, factor: Double) {
            Files.createDirectories(rows)
            Files.writeString(rows.resolve(scenario.id), "$elapsedMs $ceilingMs")
            Files.writeString(dir.resolve("results.md"), render(factor))
        }

        private fun render(factor: Double): String {
            val measured = Scenario.entries.associateWith { scenario ->
                rows.resolve(scenario.id).takeIf(Files::exists)?.let { Files.readString(it).trim().split(' ').map(String::toLong) }
            }
            val over = measured.filter { (_, row) -> row != null && row[0] >= row[1] }.keys
            return buildString {
                appendLine("### Editor performance benchmark")
                appendLine()
                appendLine(
                    "${LargeProjectFixture.LOCALES.size * LargeProjectFixture.NAMESPACES} translation files. " +
                        "Ceiling = max(${factor.format()} × reference, $CEILING_FLOOR_MS ms); references and their rationale " +
                        "in the KDoc of `HighlightingPerformanceTest`.",
                )
                appendLine()
                if (over.isNotEmpty()) {
                    appendLine("**Over the ceiling:** ${over.joinToString(", ") { it.title }}")
                    appendLine()
                }
                appendLine("| Scenario | Time | Ceiling | Reference | Status |")
                appendLine("|---|---:|---:|---:|---|")
                measured.forEach { (scenario, row) ->
                    val status = when {
                        row == null -> "not measured"
                        scenario in over -> "**over the ceiling**"
                        else -> "ok"
                    }
                    val time = row?.let { "${it[0]} ms" } ?: "–"
                    val ceiling = row?.let { "${it[1]} ms" } ?: "${scenario.ceilingMs(factor)} ms"
                    appendLine("| ${scenario.title} | $time | $ceiling | ${scenario.referenceMs} ms | $status |")
                }
            }
        }
    }

    private companion object {
        const val RUNS = 5
        const val NAMESPACED_KEYS = LargeProjectFixture.KEY_CALLS / 2

        /** See "Ceilings" in the class KDoc. */
        const val CEILING_FACTOR = 8.0
        const val CEILING_FLOOR_MS = 200L

        /** [CEILING_FACTOR], unless `I18N_PERF_FACTOR` replaces it for this run. */
        fun ceilingFactor(): Double {
            val override = System.getenv("I18N_PERF_FACTOR") ?: return CEILING_FACTOR
            return override.toDoubleOrNull()?.takeIf { it > 0 } ?: error("I18N_PERF_FACTOR must be a positive number, got '$override'")
        }

        fun Double.format(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()
    }
}
