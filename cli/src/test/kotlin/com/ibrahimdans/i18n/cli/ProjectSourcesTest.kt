package com.ibrahimdans.i18n.cli

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** The translation files found in a whole project ([ProjectSources]): roots, modules, frameworks, through the command. */
class ProjectSourcesTest {

    private val resources = "src/test/resources"
    private val exampleProject = "../examples/react-multi-namespace"
    private val example = "$exampleProject/public/locales"

    private class Run(val exit: Int, val out: String, val err: String)

    private fun check(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = I18nCheck.run(args.toList(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return Run(exit, out.toString(Charsets.UTF_8).trimEnd(), err.toString(Charsets.UTF_8).trimEnd())
    }

    /** The override's `sent` is empty in `fr`, the application's is not: two keys, never mixed. */
    @Test
    fun `Laravel package override - read under the package's namespace, apart from the application's file`() {
        val run = check("$resources/php-vendor")
        assertEquals(
            """
            Reference locale: en (2 locales)

            en: no problem
            fr: 1 empty
              empty    courier::messages:sent

            1 problem found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
    }

    @Test
    fun `project mode - Flutter catalogues found under lib, the build copies left out`(@TempDir project: Path) {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write("lib/l10n/app_en.arb", """{"@@locale": "en", "title": "App", "@title": {}, "hello": "Hello"}""")
        write("lib/l10n/app_fr.arb", """{"title": "Appli"}""")
        // Read, this copy would stop the check: it is not JSON.
        write(".dart_tool/flutter_gen/gen_l10n/app_en.arb", "{")

        val run = check(project.toString(), "--format", "json")

        assertEquals(I18nCheck.PROBLEMS, run.exit, run.err)
        val locales = JsonParser.parseString(run.out).asJsonObject["locales"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("en", "fr"), locales.map { it["locale"].asString })
        assertEquals("[\"hello\"]", locales[1]["missing"].toString())
    }

    @Test
    fun `project mode - the example's files found without --locales, per locale and namespace`() {
        assertEquals(check("--locales", example).out, check(exampleProject).out)
        val run = check(exampleProject, "--format", "json")
        val fr = JsonParser.parseString(run.out).asJsonObject["locales"].asJsonArray[1].asJsonObject
        assertEquals("[\"common:actions.eddit\",\"common:actions.saxve\"]", fr["missing"].toString())
        assertEquals("[]", fr["orphans"].toString())
        assertEquals("[\"navigation:breadrumb.home\"]", fr["empty"].toString())
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `project mode - only the translations root is read, the ignored locales are left out`() {
        val run = check("$resources/root")
        assertEquals(
            """
            Reference locale: en (2 locales)

            en: no problem
            fr: 1 missing
              missing  farewell

            1 problem found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
    }

    @Test
    fun `project mode - a module put aside has no section and its files are not read`(@TempDir project: Path) {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write("apps/web/locales/en.json", """{"a": "A", "b": "B"}""")
        write("apps/web/locales/fr.json", """{"a": "A"}""")
        write("apps/admin/locales/en.json", """{"x": "X"}""")
        write("apps/admin/locales/fr.json", "not json")
        write(
            SettingsFile.JSON_PATH,
            """{"modules": [
                {"name": "web", "rootDirectory": "apps/web", "pathTemplate": "locales/{lang}.json"},
                {"name": "admin", "rootDirectory": "apps/admin", "pathTemplate": "locales/{lang}.json", "enabled": false}
            ]}""",
        )

        val run = check(project.toString())

        assertEquals(I18nCheck.PROBLEMS, run.exit, run.err)
        assertTrue(run.out.contains("missing  b"), run.out)
        assertTrue("admin" !in run.out, run.out)
    }
}
