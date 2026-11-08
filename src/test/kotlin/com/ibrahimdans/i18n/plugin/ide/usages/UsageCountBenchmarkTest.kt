package com.ibrahimdans.i18n.plugin.ide.usages

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.IdeKeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationKeyUsages
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * How long the usage searches take on a project of known shape ([UsageBenchmarkProject]), on the
 * two paths the plugin counts along: the table's *Usage* column ([IdeKeyUsageIndex.usageCounts] then
 * [IdeKeyUsageIndex.dynamicallyReached]), and the per-key count of Code Vision and the *Unused
 * translation key* inspection ([TranslationKeyUsages]).
 *
 * The timings run at full size only with `-Pi18nBenchmark=true`, and are printed and written to
 * `build/reports/usage-benchmark.txt`. Without it, the same code runs on a small project and checks
 * that the counts match what was generated, so the project stays a fair measure.
 */
class UsageCountBenchmarkTest : PlatformBaseTest() {

    @Test
    fun `the generated project is counted as generated`() = myFixture.runWithConfig(Config()) {
        val generated = load(UsageBenchmarkProject(keysPerNamespace = 50, files = 10))

        val index = IdeKeyUsageIndex(project)
        val counts = index.usageCounts(generated.expectedCounts.keys)
        val reached = index.dynamicallyReached(counts.filterValues { it == 0 }.keys)

        assertEquals(generated.expectedCounts, counts)
        assertEquals(generated.dynamicKeys, reached)
        // A plural's two forms compose into the one key the code writes, and share its count.
        val vision = codeVisionCounts()
        val counted = vision.filterValues { it >= 0 }
        assertEquals(counted.keys.associateWith { key -> generated.expectedCounts[key] ?: generated.expectedCounts.getValue("${key}_other") }, counted)
        assertEquals(generated.dynamicKeys.filter { it in vision }.toSet(), vision.filterValues { it < 0 }.keys)
    }

    @Test
    fun `timings at full size`() {
        assumeTrue(System.getProperty(PROPERTY).toBoolean(), "timings run with -Pi18nBenchmark=true only")
        myFixture.runWithConfig(Config()) {
            val (generated, loadMs) = timed { load(UsageBenchmarkProject(keysPerNamespace = 1000, files = 300)) }
            val keys = generated.expectedCounts.keys.toList()
            val index = IdeKeyUsageIndex(project)

            val (counts, tableMs) = timed { index.usageCounts(keys) }
            // Asked again: what a count costs once the code has been read.
            val (_, warmMs) = timed { index.usageCounts(keys) }
            val orphans = counts.filterValues { it == 0 }.keys
            val (reached, dynamicMs) = timed { index.dynamicallyReached(orphans) }
            val (vision, visionMs) = timed { codeVisionCounts() }

            // Correctness is the small test's job: here a mismatch is reported, not asserted, so that
            // a long run always ends with its timings.
            val mismatches = generated.expectedCounts.count { (key, expected) -> counts[key] != expected } +
                (generated.dynamicKeys - reached).size + (reached - generated.dynamicKeys).size

            val report = """
                |Usage count benchmark — ${generated.namespaces.size} namespaces × ${generated.keysPerNamespace} keys, ${generated.files} code files
                |  mismatches against the generated truth: $mismatches
                |  project loaded and indexed: $loadMs ms
                |  table: usageCounts over ${keys.size} keys: $tableMs ms (${"%.1f".format(tableMs.toDouble() / keys.size)} ms per key)
                |  table: usageCounts again: $warmMs ms
                |  table: dynamicallyReached over ${orphans.size} orphans: $dynamicMs ms
                |  code vision: indirectCount + reachedDynamically over ${vision.size} distinct keys: $visionMs ms (${"%.1f".format(visionMs.toDouble() / vision.size)} ms per key)
                |
            """.trimMargin()
            println(report)
            System.getProperty(OUTPUT)?.let { File(it).apply { parentFile.mkdirs() }.writeText(report) }
        }
    }

    private fun load(generated: UsageBenchmarkProject): UsageBenchmarkProject {
        generated.translationFiles.forEach { (path, text) -> addFileToProject(path, text) }
        generated.codeFiles.forEach { (path, text) -> addFileToProject(path, text) }
        return generated
    }

    /** The leaf properties Code Vision would count in the first namespace's file: the first [CODE_VISION_KEYS], as measured before. */
    private fun codeVisionProperties(): List<JsonProperty> {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("locales/en/common.json"))!!
        return PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java).filter { it.value is JsonStringLiteral }.take(CODE_VISION_KEYS)
    }

    /**
     * What Code Vision computes for each key: the count of the call sites naming it, then, for a
     * key at zero, whether a dynamic literal reaches it (-1: a key nothing names, shown as dynamic
     * rather than at zero).
     */
    private fun codeVisionCounts(): Map<String, Int> = ReadAction.compute<Map<String, Int>, RuntimeException> {
        val config = Config()
        val names = codeVisionProperties().map { it.nameElement }
        val counts = TranslationKeyUsages.indirectCounts(names)
        val reached = TranslationKeyUsages.reachedDynamically(names.filter { counts[it] == 0 })
        names.associate { named -> TranslationKeyUsages.keyOf(named, config) to if (named in reached) -1 else counts.getValue(named) }
    }

    /** [block]'s result and how many milliseconds it took. */
    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - start) / 1_000_000
    }

    private companion object {
        const val PROPERTY = "i18n.benchmark"
        const val OUTPUT = "i18n.benchmark.output"
        /** As many keys as Code Vision showed per file before it read the index: the measures stay comparable. */
        const val CODE_VISION_KEYS = 100
    }
}
