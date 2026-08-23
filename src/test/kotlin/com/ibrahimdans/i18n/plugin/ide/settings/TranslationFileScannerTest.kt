package com.ibrahimdans.i18n.plugin.ide.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * What the wizard's scan refuses to walk, and what stops it.
 *
 * The folder table and the recognised extensions are already covered through
 * `SetupWizardDialogTest`; these tests cover the walk's own limits, which is where the scan
 * used to freeze the dialog: only `node_modules` and `build` were skipped, and once a folder
 * was recognised it was read to the bottom, whatever it held.
 */
class TranslationFileScannerTest {

    @Test
    fun `dependency and output folders are never walked`() {
        withTempProject { base ->
            for (skipped in listOf("dist", "out", "target", "vendor", "coverage", "node_modules", "build")) {
                translationFile(base, "$skipped/locales/en/common.json")
            }
            translationFile(base, "locales/en/common.json")

            val found = TranslationFileScanner.scan(base)

            assertTrue(found.any { it.endsWith("common.json") }, "the project's own files must still be found")
            assertTrue(
                found.none { it.contains("dist") || it.contains("out") || it.contains("target") },
                "build outputs must not be offered, got: $found"
            )
            assertTrue(
                found.none { it.contains("vendor") || it.contains("coverage") || it.contains("node_modules") },
                "dependency folders must not be offered, got: $found"
            )
        }
    }

    @Test
    fun `a recognised folder is read no deeper than the scan allows`() {
        withTempProject { base ->
            translationFile(base, "locales/fr/LC_MESSAGES/messages.po")
            translationFile(base, "locales/a/b/c/d/e/f/g/buried.json")

            val found = TranslationFileScanner.scan(base)

            assertTrue(found.any { it.endsWith("messages.po") }, "a normal layout must still be read, got: $found")
            assertFalse(found.any { it.endsWith("buried.json") }, "the walk must stop, got: $found")
        }
    }

    @Test
    fun `the scan stops when the caller cancels`() {
        withTempProject { base ->
            translationFile(base, "locales/en/common.json")

            assertThrows(CancelledScan::class.java) {
                TranslationFileScanner.scan(base) { throw CancelledScan() }
            }
        }
    }

    /** Stands in for the platform's `ProcessCanceledException`, which needs an application. */
    private class CancelledScan : RuntimeException()

    private fun translationFile(base: File, relativePath: String) {
        val file = File(base, relativePath)
        file.parentFile.mkdirs()
        file.writeText("{}")
    }

    private fun withTempProject(block: (File) -> Unit) {
        val base = Files.createTempDirectory("scanner").toFile()
        try {
            block(base)
        } finally {
            base.deleteRecursively()
        }
    }
}
