package com.ibrahimdans.i18n.plugin.ide.usages

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.IdeKeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationKeyUsages
import com.ibrahimdans.i18n.plugin.ide.inspection.UnusedTranslationKeyInspection
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInsight.codeVision.settings.CodeVisionSettings
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What the platform's reference search costs Code Vision and *Unused translation key* on a file of
 * keys nothing uses, named with a word the code writes everywhere (`title`): each one the index does
 * not find falls back to that search, which reads every code file holding the word.
 *
 * Three passes over the same keys: the first, one with no edit since (the counts kept), and one after
 * an edit of the code — what every pass cost before the counts were kept.
 *
 * Runs with `-Pi18nBenchmark=true` only, and prints its timings.
 */
class PlatformSearchCacheBenchmarkTest : PlatformBaseTest() {

    @Test
    fun `timings of the platform search over unused keys`() {
        assumeTrue(System.getProperty(PROPERTY).toBoolean(), "timings run with -Pi18nBenchmark=true only")
        myFixture.runWithConfig(Config()) {
            val code = (0 until CODE_FILES).map { i ->
                addFileToProject("src/Title$i.js", "export const title$i = 'x';\nexport function show$i(title) { return title; }\n")
            }
            addFileToProject(DEAD_FILE, (0 until DEAD_KEYS).joinToString(prefix = "{", postfix = "}") { "\"s$it\": {\"title\": \"Dead\"}" })
            val names = namesOf(DEAD_FILE)
            // The index built first: the passes measure the fallback, not reading the code.
            CodeUsageIndexService.getInstance(project).calls("", emptyList(), null)

            val passes = mutableListOf<Long>()
            repeat(PASSES_BEFORE_EDIT) { passes += timed { TranslationKeyUsages.indirectCounts(names) }.second }
            WriteCommandAction.runWriteCommandAction(project) {
                PsiDocumentManager.getInstance(project).getDocument(code.first())!!.insertString(0, "// edited\n")
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            val afterEdit = timed { TranslationKeyUsages.indirectCounts(names) }.second

            println(
                """
                |Platform search benchmark — $DEAD_KEYS unused keys named `title`, $CODE_FILES code files writing the word
                |  at most ${TranslationKeyUsages.MAX_PLATFORM_SEARCHES} searches per pass
                |${passes.mapIndexed { i, ms -> "  pass ${i + 1}${if (i == 0) " (first)" else ", no edit since"}: $ms ms" }.joinToString("\n")}
                |  pass after an edit of the code: $afterEdit ms
                |
                """.trimMargin()
            )
        }
    }

    /**
     * The first highlighting pass of *Unused translation key* over [DEAD_KEYS] keys nothing uses, on the
     * usage benchmark's project ([UsageBenchmarkProject], 300 code files), the index of the code's usages
     * built beforehand — as in the IDE, where the build runs in the background and the pass shows nothing
     * until it is done. Two files: keys spelled with a word no code file holds (`zzDead3`), and keys
     * spelled with the identifiers every code file declares (`gone3.k7`).
     *
     * Each step of the inspection is timed on its own, on a fresh copy of the file (its own elements, so
     * nothing kept on them from another step), in the order the inspection runs them; then a whole
     * highlighting pass with the inspection, Code Vision off, and the same pass with Code Vision on, each
     * on a fresh copy too.
     *
     * Measured on 2026-11-08 (main at #892, three runs): the steps take 0.4 to 1.4 s (rare words) and 1.5 to
     * 1.8 s (common words), `indirectCounts` most of it; a whole pass, Code Vision off, 1.3 to 1.4 s and 2.2 to
     * 2.4 s. The same pass with Code Vision on takes 63 to 72 s and 56 to 62 s, and 54 s with the inspection
     * off: the ~3 minutes once measured were not the inspection's. Threads sampled
     * every 100 ms during that pass sat, 445 samples of 470, under `CodeVisionHost.onEditorCreated` →
     * `EditorCodeVisionContext.resubmitThings` → `CodeVisionView.runWithReusingLenses` →
     * `CodeVisionVisualVerticalPositionKeeper.keep`, on the event dispatch thread: the platform placing
     * the 500 lenses in the editor the fixture just opened, about 110 ms each, once per editor.
     *
     * That cost belongs to the test's editor, which is never laid out on screen. Checked on 2026-11-08 in
     * WebStorm 2026.3 (`runWebStorm` on a virtual display, the usage index built): reopening a file of 500
     * keys, the event dispatch thread was busy in 7 of 199 samples (one every ~450 ms), 1 of them in
     * `CodeVisionVisualVerticalPositionKeeper.keep`; a file of 2 000 keys showed all its lenses within
     * 25 s with none in `keep`, and the IDE recorded no freeze. So Code Vision keeps a lens on every key,
     * however large the file: nothing to cap.
     */
    @Test
    fun `first pass of the unused key inspection, step by step`() {
        assumeTrue(System.getProperty(PROPERTY).toBoolean(), "timings run with -Pi18nBenchmark=true only")
        myFixture.runWithConfig(Config()) {
            val generated = UsageBenchmarkProject(keysPerNamespace = 1000, files = 300)
            generated.translationFiles.forEach { (path, text) -> addFileToProject(path, text) }
            generated.codeFiles.forEach { (path, text) -> addFileToProject(path, text) }
            val files = mapOf("rare words" to rareWordKeys(), "common words" to commonWordKeys())
            files.forEach { (kind, text) -> COPIES.forEach { copy -> addFileToProject(pathOf(kind, copy), text) } }
            val (_, buildMs) = timed { IdeKeyUsageIndex(project).usageCounts(generated.expectedCounts.keys) }

            val lines = mutableListOf("  index built before the passes: $buildMs ms")
            myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
            files.keys.forEach { kind ->
                lines += "  $kind, $DEAD_KEYS unused keys:"
                lines += steps(pathOf(kind, "steps"))
                val vision = CodeVisionSettings.getInstance()
                val enabled = vision.codeVisionEnabled
                vision.codeVisionEnabled = false
                try {
                    val (reported, firstMs) = timed { highlight(pathOf(kind, "novision")) }
                    val (_, againMs) = timed { highlight(pathOf(kind, "novision")) }
                    lines += "    whole pass, Code Vision off: $firstMs ms ($reported reported), again unmodified: $againMs ms"
                } finally {
                    vision.codeVisionEnabled = enabled
                }
                val (_, visionMs) = timed { highlight(pathOf(kind, "vision")) }
                lines += "    whole pass, Code Vision on: $visionMs ms"
            }
            val report = (listOf("Unused key inspection, first pass — ${generated.files} code files, at most ${TranslationKeyUsages.MAX_PLATFORM_SEARCHES} fallback searches per question") + lines)
                .joinToString("\n", postfix = "\n")
            println(report)
            System.getProperty(OUTPUT)?.let { File(it).resolveSibling("unused-inspection-first-pass.txt").apply { parentFile.mkdirs() }.writeText(report) }
        }
    }

    /** The inspection's steps over [path], in its order: every leaf counted, then the keys still at zero asked the rest. */
    private fun steps(path: String): List<String> {
        val names = namesOf(path)
        val (counts, indirectMs) = timed { TranslationKeyUsages.indirectCounts(names) }
        val zero = names.filter { counts[it] == 0 }
        val (_, keptMs) = timed { zero.forEach { TranslationKeyUsages.kept(it) } }
        val (unnamed, ownMs) = timed { ReadAction.compute<List<PsiElement>, RuntimeException> { zero.filter { TranslationKeyUsages.count(it, limit = 1) == 0 } } }
        val (_, dynamicMs) = timed { TranslationKeyUsages.reachedDynamically(unnamed) }
        return listOf(
            "    indirectCounts of ${names.size} keys: $indirectMs ms (${zero.size} at zero)",
            "    kept of ${zero.size}: $keptMs ms",
            "    count(limit = 1) of ${zero.size}: $ownMs ms (${unnamed.size} at zero)",
            "    reachedDynamically of ${unnamed.size}: $dynamicMs ms",
        )
    }

    private fun namesOf(path: String): List<PsiElement> = ReadAction.compute<List<PsiElement>, RuntimeException> {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!
        PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java).filter { it.value is JsonStringLiteral }.map { it.nameElement }
    }

    private fun rareWordKeys(): String = (0 until DEAD_KEYS).joinToString(",\n", "{\n", "\n}") { "  \"zzDead$it\": \"Dead\"" }

    /** [DEAD_KEYS] keys under sections of their own, named like the identifiers each code file declares. */
    private fun commonWordKeys(): String {
        val size = UsageBenchmarkProject.SECTION_SIZE
        return (0 until DEAD_KEYS / size).joinToString(",\n", "{\n", "\n}") { section ->
            (0 until size).joinToString(",\n", "  \"gone$section\": {\n", "\n  }") { "    \"k$it\": \"Dead\"" }
        }
    }

    private fun pathOf(kind: String, copy: String) = "locales/en/dead-${kind.substringBefore(' ')}-$copy.json"

    /** How many keys *Unused translation key* reports in [path], one highlighting pass of it. */
    private fun highlight(path: String): Int {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
        val message = PluginBundle.getMessage("inspection.unused.message")
        return myFixture.doHighlighting().count { it.description == message }
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
        const val DEAD_FILE = "locales/en/dead.json"
        const val DEAD_KEYS = 500
        const val CODE_FILES = 100
        /** Enough passes for the limit to have searched every key once: 500 / 20. */
        const val PASSES_BEFORE_EDIT = 27
        /** One file per measure, so that nothing kept on the elements by one measure serves another. */
        val COPIES = listOf("steps", "novision", "vision")
    }
}
