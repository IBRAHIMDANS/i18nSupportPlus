package com.ibrahimdans.i18n.plugin.ide.usages

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationKeyUsages
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
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
        assumeTrue(System.getProperty("i18n.benchmark").toBoolean(), "timings run with -Pi18nBenchmark=true only")
        myFixture.runWithConfig(Config()) {
            val code = (0 until CODE_FILES).map { i ->
                addFileToProject("src/Title$i.js", "export const title$i = 'x';\nexport function show$i(title) { return title; }\n")
            }
            addFileToProject(DEAD_FILE, (0 until DEAD_KEYS).joinToString(prefix = "{", postfix = "}") { "\"s$it\": {\"title\": \"Dead\"}" })
            val names = ReadAction.compute<List<PsiElement>, RuntimeException> {
                val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(DEAD_FILE))!!
                PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java).filter { it.value is JsonStringLiteral }.map { it.nameElement }
            }
            // The index built first: the passes measure the fallback, not reading the code.
            CodeUsageIndexService.getInstance(project).calls("", emptyList(), null)

            val passes = mutableListOf<Long>()
            repeat(PASSES_BEFORE_EDIT) { passes += timed { TranslationKeyUsages.indirectCounts(names) } }
            WriteCommandAction.runWriteCommandAction(project) {
                PsiDocumentManager.getInstance(project).getDocument(code.first())!!.insertString(0, "// edited\n")
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            val afterEdit = timed { TranslationKeyUsages.indirectCounts(names) }

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

    private inline fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private companion object {
        const val DEAD_FILE = "locales/en/dead.json"
        const val DEAD_KEYS = 500
        const val CODE_FILES = 100
        /** Enough passes for the limit to have searched every key once: 500 / 20. */
        const val PASSES_BEFORE_EDIT = 27
    }
}
