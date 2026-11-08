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

/**
 * The command end to end: text and JSON reports, exit codes, flags, settings and errors. A case about one
 * class (a format, the project's files, the comparison, `--code`) lives in that class's test.
 */
class I18nCheckTest {

    private val resources = "src/test/resources"
    private val exampleProject = "../examples/react-multi-namespace"

    /**
     * The 1-based line where [fragment] is written, once, in [path] under [project]. The examples are
     * documentation and get edited (#698 added a comment line): reading the line off the file keeps
     * these tests about the line the report gives, not about the example's layout.
     */
    private fun lineOf(project: String, path: String, fragment: String): Int {
        val text = java.io.File(project, path).readText()
        val offset = text.indexOf(fragment)
        check(offset >= 0 && text.indexOf(fragment, offset + 1) < 0) { "'$fragment' is not written once in $path" }
        return text.substring(0, offset).count { it == '\n' } + 1
    }

    private val unknownKeyLine get() = lineOf(exampleProject, "src/components/Dashboard.tsx", "'dashboardd:statss.users.count'")
    private val example = "$exampleProject/public/locales"

    private class Run(val exit: Int, val out: String, val err: String)

    private fun check(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = I18nCheck.run(args.toList(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return Run(exit, out.toString(Charsets.UTF_8).trimEnd(), err.toString(Charsets.UTF_8).trimEnd())
    }

    @Test
    fun `example project - text report of missing and empty keys per namespace`() {
        val run = check("--locales", example)
        assertEquals(
            """
            Reference locale: en (2 locales)

            en: no problem
            fr: 2 missing, 1 empty
              missing  common:actions.eddit
              missing  common:actions.saxve
              empty    navigation:breadrumb.home

            3 problems found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `example project - JSON report`() {
        val run = check("--locales", example, "--format", "json")
        val expected = """
            {
              "reference": "en",
              "problems": 3,
              "locales": [
                {"locale": "en", "missing": [], "orphans": [], "empty": [], "values": []},
                {
                  "locale": "fr",
                  "missing": ["common:actions.eddit", "common:actions.saxve"],
                  "orphans": [],
                  "empty": ["navigation:breadrumb.home"],
                  "values": []
                }
              ]
            }
        """
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(run.out))
    }

    @Test
    fun `French as the reference - the English-only keys are orphans`() {
        val run = check("--locales", example, "--reference", "fr", "--format", "json")
        val en = JsonParser.parseString(run.out).asJsonObject["locales"].asJsonArray[0].asJsonObject
        assertEquals("[\"common:actions.eddit\",\"common:actions.saxve\"]", en["orphans"].toString())
    }

    @Test
    fun `exit code - 0 while the problems stay within the threshold`() {
        assertEquals(I18nCheck.OK, check("--locales", example, "--max-problems", "3").exit)
        assertEquals(I18nCheck.PROBLEMS, check("--locales", example, "--max-problems", "2").exit)
    }

    @Test
    fun `exit code - 0 and a short line when nothing is wrong`() {
        val run = check("--locales", "$resources/clean")
        assertEquals("Reference locale: en (2 locales)\n\nen: no problem\nfr: no problem\n\nNo problem found.", run.out)
        assertEquals(I18nCheck.OK, run.exit)
    }

    @Test
    fun `settings - the module's reference locale and key separator are taken`() {
        val run = check("$resources/settings", "--locales", "$resources/settings/locales", "--format", "json")
        val report = JsonParser.parseString(run.out).asJsonObject
        assertEquals("fr", report["reference"].asString)
        assertEquals("[\"menu/help\"]", report["locales"].asJsonArray[0].asJsonObject["missing"].toString())
    }

    @Test
    fun `settings - the flag overrides the settings' reference locale`() {
        val run = check("$resources/settings", "--locales", "$resources/settings/locales", "--reference", "en", "--format", "json")
        val report = JsonParser.parseString(run.out).asJsonObject
        assertEquals("en", report["reference"].asString)
        assertEquals("[\"menu/help\"]", report["locales"].asJsonArray[1].asJsonObject["orphans"].toString())
    }

    // ── project mode ──────────────────────────────────────────────────────────

    @Test
    fun `project mode - one section per module, each with its reference locale`() {
        val run = check("$resources/modules")
        assertEquals(
            """
            Module web: reference locale en (2 locales)

            en: no problem
            fr: 1 missing
              missing  cart

            Module admin: reference locale fr (2 locales)

            en: 1 missing, 1 orphan
              missing  roles
              orphan   logs
            fr: no problem

            3 problems found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `project mode - JSON of several modules`() {
        val run = check("$resources/modules", "--format", "json")
        val expected = """
            {
              "problems": 3,
              "modules": [
                {
                  "module": "web", "reference": "en", "problems": 1,
                  "locales": [
                    {"locale": "en", "missing": [], "orphans": [], "empty": [], "values": []},
                    {"locale": "fr", "missing": ["cart"], "orphans": [], "empty": [], "values": []}
                  ]
                },
                {
                  "module": "admin", "reference": "fr", "problems": 2,
                  "locales": [
                    {"locale": "en", "missing": ["roles"], "orphans": ["logs"], "empty": [], "values": []},
                    {"locale": "fr", "missing": [], "orphans": [], "empty": [], "values": []}
                  ]
                }
              ]
            }
        """
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(run.out))
    }

    @Test
    fun `project mode - no translation file, exit 2`() {
        val run = check("$resources/empty")
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertTrue(run.err.startsWith("i18n-check: no translation file in $resources/empty"), run.err)
    }

    @Test
    fun `code - the example's unused keys and the call to an unknown one`() {
        val run = check(exampleProject, "--code")
        assertEquals(
            """
            Reference locale: en (2 locales)

            en: no problem
            fr: 2 missing, 1 empty
              missing  common:actions.eddit
              missing  common:actions.saxve
              empty    navigation:breadrumb.home

            Unused keys: 9
              auth:errors.accountLocked
              common:actions.eddit
              common:actions.save
              common:actions.saxve
              common:status.empty
              common:status.error
              common:status.loading
              dashboard:stats.files.label
              navigation:breadcrumb.home

            Unknown keys: 1
              src/components/Dashboard.tsx:$unknownKeyLine  dashboardd:statss.users.count

            13 problems found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `code - JSON adds the unused and unknown keys to the existing fields`() {
        val report = JsonParser.parseString(check(exampleProject, "--code", "--format", "json").out).asJsonObject
        assertEquals(setOf("reference", "problems", "locales", "unused", "unknown"), report.keySet())
        assertEquals(13, report["problems"].asInt)
        assertEquals(9, report["unused"].asJsonArray.size())
        assertEquals(
            JsonParser.parseString("""[{"key": "dashboardd:statss.users.count", "file": "src/components/Dashboard.tsx", "line": $unknownKeyLine}]"""),
            report["unknown"],
        )
    }

    @Test
    fun `code - exit code counts the unused and unknown keys`() {
        assertEquals(I18nCheck.OK, check(exampleProject, "--code", "--max-problems", "13").exit)
        assertEquals(I18nCheck.PROBLEMS, check(exampleProject, "--code", "--max-problems", "12").exit)
    }

    @Test
    fun `no translation file - a clear message and exit 2`() {
        val run = check("--locales", "$resources/empty")
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertTrue(run.err.startsWith("i18n-check: no translation file in $resources/empty"), run.err)
    }

    private fun gbProject(project: Path) {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write("locales/en/common.json", """{"title": "Colour", "item_one": "{{count}} item", "item_other": "{{count}} items"}""")
        write("locales/fr/common.json", """{"title": "Couleur", "item_one": "{{count}} élément", "item_other": "{{count}} éléments"}""")
        write("locales/gb/common.json", """{"item_other": "{{count}} items"}""")
    }

    @Test
    fun `country code folder - not checked without an alias, and said so`(@TempDir project: Path) {
        gbProject(project)

        val run = check(project.toString())

        assertEquals(I18nCheck.OK, run.exit, run.out)
        assertTrue(run.out.contains("(2 locales)"), run.out)
        assertTrue(run.err.contains("locales/gb not checked, 'gb' is a country code, not a language code"), run.err)
    }

    @Test
    fun `country code folder - checked under its alias, with the plural forms of its locale`(@TempDir project: Path) {
        gbProject(project)
        // Off: en-GB would inherit from en, and the plural forms of gb would not be compared.
        Files.writeString(project.resolve(SettingsFile.JSON_PATH), """{"localeAliases": "gb=en-GB", "regionalFallback": false}""")

        val run = check(project.toString())

        assertEquals(I18nCheck.PROBLEMS, run.exit, run.out)
        assertTrue(run.out.contains("(3 locales)"), run.out)
        assertTrue(run.out.contains("gb: 2 missing"), run.out)
        assertTrue(run.out.contains("missing  common:item_one"), run.out)
        assertTrue(run.out.contains("missing  common:title"), run.out)
        assertTrue(run.err.isEmpty(), run.err)
    }

    @Test
    fun `invalid i18n-check json - named, exit 2`(@TempDir project: Path) {
        Files.writeString(project.resolve(SettingsFile.JSON_PATH), """{ "keySeperator": "/" }""")
        val run = check(project.toString())
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertTrue(run.err.startsWith("i18n-check: cannot read i18n-check.json: unknown setting 'keySeperator'"), run.err)
    }

    @Test
    fun `unknown reference locale - exit 2`() {
        val run = check("--locales", example, "--reference", "de")
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertEquals("i18n-check: reference locale de not found among en, fr", run.err)
    }

    @Test
    fun `usage errors - exit 2 with the usage`() {
        listOf(
            arrayOf("--locales"),
            arrayOf("--locales", example, "--code"),
            arrayOf("--locales", example, "--format", "xml"),
            arrayOf("--locales", example, "--max-problems", "-1"),
            arrayOf("--locales", example, "--verbose"),
            arrayOf("a", "b", "--locales", example),
        ).forEach { args ->
            val run = check(*args)
            assertEquals(I18nCheck.CANNOT_CHECK, run.exit, args.joinToString(" "))
            assertTrue(run.err.contains("Usage: i18n-check"), run.err)
        }
    }

    @Test
    fun `help - the usage on the standard output, exit 0`() {
        val run = check("--help")
        assertEquals(I18nCheck.OK, run.exit)
        assertTrue(run.out.startsWith("Usage: i18n-check"))
    }

    /** `en.json` and `fr.json` in [dir], holding [en] and [fr]; the check run on them with [args]. */
    private fun checkValues(dir: Path, en: String, fr: String, vararg args: String): Run {
        Files.writeString(dir.resolve("en.json"), en)
        Files.writeString(dir.resolve("fr.json"), fr)
        return check("--locales", dir.toString(), *args)
    }

    @Test
    fun `values - JSON lists them per locale`(@TempDir dir: Path) {
        val run = checkValues(dir, """{ "a": "Hi {name} {age}" }""", """{ "a": "Salut {name}" }""", "--format", "json")
        val root = JsonParser.parseString(run.out).asJsonObject
        assertEquals(1, root["problems"].asInt)
        val fr = root["locales"].asJsonArray.map { it.asJsonObject }.single { it["locale"].asString == "fr" }
        val value = fr["values"].asJsonArray.single().asJsonObject
        assertEquals("a", value["key"].asString)
        assertEquals("Placeholder '{age}' of the reference locale is missing", value["message"].asString)
    }

    @Test
    fun `values - --no-values leaves them out of the report and of the exit code`(@TempDir dir: Path) {
        val run = checkValues(dir, """{ "a": "Hi {name} {age}" }""", """{ "a": "Salut {name}" }""", "--no-values")
        assertEquals(I18nCheck.OK, run.exit, run.out)
        assertTrue("Placeholder" !in run.out, run.out)
    }

    @Test
    fun `values - an ignored locale is left out`(@TempDir dir: Path) {
        Files.createDirectories(dir.resolve("locales"))
        Files.writeString(dir.resolve("i18n-check.json"), """{ "ignoredLocales": "fr" }""")
        Files.writeString(dir.resolve("locales/en.json"), """{ "a": "Hi {name} {age}" }""")
        Files.writeString(dir.resolve("locales/fr.json"), """{ "a": "Salut {name}" }""")
        assertEquals(I18nCheck.OK, check(dir.toString()).exit)
    }
}
