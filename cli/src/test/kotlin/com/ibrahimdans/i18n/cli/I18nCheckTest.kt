package com.ibrahimdans.i18n.cli

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class I18nCheckTest {

    private val resources = "src/test/resources"
    private val example = "../examples/react-multi-namespace/public/locales"

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
    fun `unknown reference locale - exit 2`() {
        val run = check("--locales", example, "--reference", "de")
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertEquals("i18n-check: reference locale de not found among en, fr", run.err)
    }

    @Test
    fun `usage errors - exit 2 with the usage`() {
        listOf(
            arrayOf<String>(),
            arrayOf("--locales"),
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
