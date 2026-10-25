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

class I18nCheckTest {

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
                {"locale": "en", "missing": [], "orphans": [], "empty": []},
                {
                  "locale": "fr",
                  "missing": ["common:actions.eddit", "common:actions.saxve"],
                  "orphans": [],
                  "empty": ["navigation:breadrumb.home"]
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
    fun `flat layout - YAML and JSON side by side, other files left alone`() {
        val run = check("--locales", "$resources/flat")
        assertEquals(
            """
            Reference locale: en (2 locales)

            de: 1 missing, 1 orphan, 1 empty
              missing  footer
              orphan   header
              empty    app.subtitle
            en: no problem

            3 problems found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
    }

    @Test
    fun `JSON5 - comments, unquoted keys and single quotes read, the missing key reported`() {
        val run = check("--locales", "$resources/json5")
        assertEquals(
            """
            Reference locale: en (2 locales)

            en: no problem
            fr: 1 missing
              missing  menu.close

            1 problem found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `plural forms - each locale needs the categories of its own language`() {
        val run = check("--locales", "$resources/plurals", "--format", "json")
        val locales = JsonParser.parseString(run.out).asJsonObject["locales"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("en", "ja", "ru"), locales.map { it["locale"].asString })
        assertEquals("[]", locales[1]["missing"].toString())
        assertEquals("[\"item_few\",\"item_many\"]", locales[2]["missing"].toString())
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
                    {"locale": "en", "missing": [], "orphans": [], "empty": []},
                    {"locale": "fr", "missing": ["cart"], "orphans": [], "empty": []}
                  ]
                },
                {
                  "module": "admin", "reference": "fr", "problems": 2,
                  "locales": [
                    {"locale": "en", "missing": ["roles"], "orphans": ["logs"], "empty": []},
                    {"locale": "fr", "missing": [], "orphans": [], "empty": []}
                  ]
                }
              ]
            }
        """
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(run.out))
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
    fun `project mode - no translation file, exit 2`() {
        val run = check("$resources/empty")
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertTrue(run.err.startsWith("i18n-check: no translation file in $resources/empty"), run.err)
    }

    // ── --code ────────────────────────────────────────────────────────────────

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
              src/components/Dashboard.tsx:27  dashboardd:statss.users.count

            13 problems found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `code - a key a template literal may build is not unused`() {
        val unused = JsonParser.parseString(check(exampleProject, "--code", "--format", "json").out).asJsonObject["unused"].asJsonArray
        assertTrue(unused.none { it.asString.startsWith("deposit-box:status.") }, unused.toString())
    }

    @Test
    fun `code - JSON adds the unused and unknown keys to the existing fields`() {
        val report = JsonParser.parseString(check(exampleProject, "--code", "--format", "json").out).asJsonObject
        assertEquals(setOf("reference", "problems", "locales", "unused", "unknown"), report.keySet())
        assertEquals(13, report["problems"].asInt)
        assertEquals(9, report["unused"].asJsonArray.size())
        assertEquals(
            JsonParser.parseString("""[{"key": "dashboardd:statss.users.count", "file": "src/components/Dashboard.tsx", "line": 27}]"""),
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

    @Test
    fun `invalid translation file - named, exit 2`() {
        val run = check("--locales", "$resources/invalid")
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertTrue(run.err.contains("not a valid JSON translation file"), run.err)
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
}
