package com.ibrahimdans.i18n.plugin.build

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.regex.PatternSyntaxException

/**
 * Guards `verifier-ignored-problems.txt`, whose mistakes are invisible until a release.
 *
 * The Plugin Verifier compiles every line of that file as a regex, skipping only blank lines and
 * lines starting with `//`. A malformed pattern throws while the options are being read, so
 * `verifyPlugin` dies after two minutes without a report and with a stack trace that names the
 * verifier's own parser rather than the file — which is how three unescaped `(` survived there
 * long enough for the task to have never verified anything. A `#` comment is not skipped either:
 * it is compiled like any other line, so a sentence of prose can fail the whole run.
 *
 * These tests cost nothing and fail in seconds instead.
 */
class VerifierIgnoredProblemsTest {

    private val file = File("verifier-ignored-problems.txt")

    /** Lines the verifier actually turns into conditions: neither blank nor `//`. */
    private fun conditionLines(): List<Pair<Int, String>> {
        assertTrue(file.isFile, "expected the ignored-problems file at ${file.absolutePath}")
        return file.readLines()
            .mapIndexed { index, line -> index + 1 to line.trim() }
            .filterNot { (_, line) -> line.isEmpty() || line.startsWith("//") }
    }

    @Test
    fun `every condition compiles as a regex`() {
        val broken = conditionLines().mapNotNull { (number, line) ->
            try {
                Regex(line.substringAfterLast(':'))
                null
            } catch (e: PatternSyntaxException) {
                "line $number: $line — ${e.description}"
            }
        }

        assertTrue(
            broken.isEmpty(),
            "these lines are compiled as regexes and do not parse, which kills verifyPlugin before " +
                "it verifies anything:\n" + broken.joinToString("\n")
        )
    }

    @Test
    fun `no comment uses the hash sign`() {
        val hashes = file.readLines()
            .mapIndexed { index, line -> index + 1 to line.trim() }
            .filter { (_, line) -> line.startsWith("#") }
            .map { (number, line) -> "line $number: $line" }

        assertTrue(
            hashes.isEmpty(),
            "the verifier only skips blank lines and `//` — a `#` line is compiled as a condition:\n" +
                hashes.joinToString("\n")
        )
    }
}
