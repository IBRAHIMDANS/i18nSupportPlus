package com.ibrahimdans.i18n.plugin.ide.usages

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.IdeKeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationKeyUsages
import com.ibrahimdans.i18n.plugin.ide.inspection.UnusedTranslationKeyInspection
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.keyScopesOf
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.ASTNode
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
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
            myFixture.addFileToProject(DEAD_FILE, (0 until DEAD_KEYS).joinToString(prefix = "{", postfix = "}") { "\"zzDead$it\": \"Dead\"" })
            val keys = generated.expectedCounts.keys.toList()
            val index = IdeKeyUsageIndex(project)
            myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)

            // What the first pass over a translation file costs the highlighting before anything read the index:
            // the build itself runs in the background, held here so that the pass is measured alone.
            val service = CodeUsageIndexService.getInstance(project)
            service.forget()
            service.holdBuilds = true
            val (coldWarnings, coldInspectionMs) = try { timed { highlight(COMMON_FILE) } } finally { service.holdBuilds = false }

            // The heap the index takes: measured around its build, the first count. The code's syntax trees are
            // parsed and held beforehand, and read once by a throwaway indexer so that the caches the build fills
            // on them (hook resolution, injections) are there on both sides: what remains is the index.
            val (trees, rawHeapMb) = warmedTrees(generated.codeFiles.keys)
            val heapBefore = usedHeap()
            val (counts, tableMs) = timed { index.usageCounts(keys) }
            val heapAfter = usedHeap()
            val (codeSites, linkedSites) = service.sitesHeld()
            val heapDelta = (heapAfter - heapBefore).coerceAtLeast(0)
            check(trees.isNotEmpty())
            // Asked again: what a count costs once the code has been read.
            val (_, warmMs) = timed { index.usageCounts(keys) }
            val orphans = counts.filterValues { it == 0 }.keys
            val (reached, dynamicMs) = timed { index.dynamicallyReached(orphans) }
            val (vision, visionMs) = timed { codeVisionCounts() }
            val (_, warmInspectionMs) = timed { highlight(COMMON_FILE) }
            // A file whose keys the code never names: each one falls back to the platform's reference search.
            val (_, deadVisionMs) = timed { codeVisionCounts(DEAD_FILE, DEAD_KEYS) }
            val (deadWarnings, deadInspectionMs) = timed { highlight(DEAD_FILE) }

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
                |  index: $codeSites call sites in the code, $linkedSites in translation values
                |  index: heap used before the build ${heapBefore / MB} MB, after ${heapAfter / MB} MB, +${"%.1f".format(heapDelta.toDouble() / MB)} MB (${heapDelta / (codeSites + linkedSites).coerceAtLeast(1)} bytes per site, syntax trees and their caches held on both sides)
                |  index: a throwaway read of the same files, trees parsed beforehand, took +${"%.1f".format(rawHeapMb)} MB (the caches it fills, then the index)
                |  table: dynamicallyReached over ${orphans.size} orphans: $dynamicMs ms
                |  code vision: indirectCount + reachedDynamically over ${vision.size} distinct keys: $visionMs ms (${"%.1f".format(visionMs.toDouble() / vision.size)} ms per key)
                |  unused key inspection over $COMMON_FILE, index not built yet (built in the background): $coldInspectionMs ms ($coldWarnings reported)
                |  unused key inspection over $COMMON_FILE, index built: $warmInspectionMs ms
                |  code vision over $DEAD_KEYS unused keys: $deadVisionMs ms
                |  unused key inspection over $DEAD_KEYS unused keys: $deadInspectionMs ms ($deadWarnings reported)
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

    /** The leaf properties Code Vision would count in [path]: the first [keys], as measured before. */
    private fun codeVisionProperties(path: String, keys: Int): List<JsonProperty> {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!
        return PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java).filter { it.value is JsonStringLiteral }.take(keys)
    }

    /** How many keys *Unused translation key* reports in [path], one highlighting pass of it. */
    private fun highlight(path: String): Int {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
        val message = PluginBundle.getMessage("inspection.unused.message")
        return myFixture.doHighlighting().count { it.description == message }
    }

    /**
     * What Code Vision computes for each key: the count of the call sites naming it, then, for a
     * key at zero, whether a dynamic literal reaches it (-1: a key nothing names, shown as dynamic
     * rather than at zero).
     */
    private fun codeVisionCounts(path: String = COMMON_FILE, keys: Int = CODE_VISION_KEYS): Map<String, Int> = ReadAction.compute<Map<String, Int>, RuntimeException> {
        val config = Config()
        val names = codeVisionProperties(path, keys).map { it.nameElement }
        val counts = TranslationKeyUsages.indirectCounts(names)
        val reached = TranslationKeyUsages.reachedDynamically(names.filter { counts[it] == 0 })
        names.associate { named -> TranslationKeyUsages.keyOf(named, config) to if (named in reached) -1 else counts.getValue(named) }
    }

    /**
     * The syntax trees of the files at [paths], parsed in full and held so that a collection keeps them, then
     * read once by a throwaway indexer; with what that read added to the heap, in megabytes.
     */
    private fun warmedTrees(paths: Collection<String>): Pair<List<ASTNode>, Double> = ReadAction.compute<Pair<List<ASTNode>, Double>, RuntimeException> {
        val files = paths.mapNotNull { path -> PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path)) }
        val trees = files.map { file -> file.accept(object : PsiRecursiveElementWalkingVisitor() {}); file.node }
        val before = usedHeap()
        val indexer = CodeUsageIndexer(project, Settings.getInstance(project).config(), keyScopesOf(project))
        val read = files.map(indexer::sitesOf)
        val added = (usedHeap() - before).coerceAtLeast(0).toDouble() / MB
        check(read.size == files.size)
        trees to added
    }

    /** The heap in use after two collections, in bytes. */
    private fun usedHeap(): Long {
        repeat(2) { System.gc() }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
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
        const val COMMON_FILE = "locales/en/common.json"
        /** A file of keys nothing uses, as large as the provider's former limit times five. */
        const val DEAD_FILE = "locales/en/dead.json"
        const val DEAD_KEYS = 500
        const val MB = 1024 * 1024
    }
}
