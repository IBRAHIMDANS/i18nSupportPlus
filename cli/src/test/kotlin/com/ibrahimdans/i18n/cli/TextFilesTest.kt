package com.ibrahimdans.i18n.cli

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * A file that is not UTF-8 — a minified `.js` with a stray byte, a Latin-1 manifest — used to end `i18n-check`
 * with a stack trace: code is read with replacement characters, a manifest or a translation file stops the
 * check naming the file.
 */
class TextFilesTest {

    private class Run(val exit: Int, val out: String, val err: String)

    private fun check(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = I18nCheck.run(args.toList(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return Run(exit, out.toString(Charsets.UTF_8).trimEnd(), err.toString(Charsets.UTF_8).trimEnd())
    }

    private fun Path.write(path: String, text: String) =
        resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }

    /** [text] in Latin-1: its `é` is a lone byte no UTF-8 sequence holds. */
    private fun Path.writeLatin1(path: String, text: String) =
        resolve(path).also { Files.createDirectories(it.parent) }.let { Files.write(it, text.toByteArray(Charsets.ISO_8859_1)) }

    /** The code in [project] reads `title`, which both locales hold: nothing to report. */
    private fun cleanProject(project: Path) {
        project.write("src/app.ts", "t('title')\n")
        project.write("locales/en.json", """{"title": "Shop"}""")
        project.write("locales/fr.json", """{"title": "Boutique"}""")
    }

    @Test
    fun `code that is not UTF-8 is read with replacement characters, and said`(@TempDir dir: Path) {
        val file = dir.writeLatin1("vendor.min.js", "t('café')")
        val warnings = mutableListOf<String>()

        val text = TextFiles.readOrNull(file, "vendor.min.js") { warnings += it }

        Assertions.assertEquals("t('caf�')", text)
        Assertions.assertEquals(listOf("vendor.min.js is not UTF-8: its code is read with replacement characters"), warnings)
    }

    @Test
    fun `code that cannot be read is left out, and said`(@TempDir dir: Path) {
        val warnings = mutableListOf<String>()

        val text = TextFiles.readOrNull(dir.resolve("gone.js"), "gone.js") { warnings += it }

        Assertions.assertNull(text)
        Assertions.assertEquals(listOf("cannot read gone.js (no such file): its code is not checked"), warnings)
    }

    @Test
    fun `a manifest that is not UTF-8 stops the check naming it, a UTF-8 one is read whole`(@TempDir dir: Path) {
        val utf8 = dir.write("package.json", """{"name": "café"}""")
        val latin1 = dir.writeLatin1("composer.json", """{"name": "café"}""")

        Assertions.assertEquals("""{"name": "café"}""", TextFiles.read(utf8))
        val error = Assertions.assertThrows(CannotCheck::class.java) { TextFiles.read(latin1, "composer.json") }
        Assertions.assertEquals("cannot read composer.json: not valid UTF-8", error.message)
    }

    @Test
    fun `a source file with invalid bytes leaves the check complete, with a warning naming it and the same exit code`(@TempDir project: Path) {
        cleanProject(project)
        // The only use of `extra`: a file given up would leave it unused.
        project.writeLatin1("src/vendor.min.js", "t('extra') // café\n")
        project.write("locales/en.json", """{"title": "Shop", "extra": "More"}""")
        project.write("locales/fr.json", """{"title": "Boutique", "extra": "Plus"}""")
        val stderr = ByteArrayOutputStream()
        val systemErr = System.err

        System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
        val run = try {
            check(project.toString(), "--code")
        } finally {
            System.setErr(systemErr)
        }

        Assertions.assertEquals(I18nCheck.OK, run.exit, run.err)
        Assertions.assertTrue(run.out.endsWith("No problem found."), run.out)
        Assertions.assertEquals(
            "i18n-check: src/vendor.min.js is not UTF-8: its code is read with replacement characters",
            stderr.toString(Charsets.UTF_8).trimEnd(),
        )
    }

    @Test
    fun `a manifest with invalid bytes stops the check with its name, not a stack trace`(@TempDir project: Path) {
        cleanProject(project)
        project.writeLatin1("package.json", """{"name": "café", "dependencies": {"@angular/core": "^18"}}""")

        val run = check(project.toString(), "--code")

        Assertions.assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        Assertions.assertEquals("i18n-check: cannot read package.json: not valid UTF-8", run.err)
    }

    @Test
    fun `a translation file with invalid bytes stops the check with its name`(@TempDir project: Path) {
        cleanProject(project)
        val file = project.writeLatin1("locales/fr.json", """{"title": "Boutique française"}""")

        val run = check("--locales", project.resolve("locales").toString())

        Assertions.assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        Assertions.assertEquals("i18n-check: cannot read $file: not valid UTF-8", run.err)
    }

    @Test
    fun `an angular json with invalid bytes stops the check with its name`(@TempDir project: Path) {
        cleanProject(project)
        project.writeLatin1("angular.json", """{ "version": 1, "projects": { "café": { "root": "", "i18n": { "sourceLocale": "en" } } } }""")

        val run = check(project.toString())

        Assertions.assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        Assertions.assertEquals("i18n-check: cannot read angular.json: not valid UTF-8", run.err)
    }
}
