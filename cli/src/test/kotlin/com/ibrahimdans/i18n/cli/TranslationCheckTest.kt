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

/** What a locale is compared against ([TranslationCheck]): plural forms, values, regional parents, through the command. */
class TranslationCheckTest {

    private val resources = "src/test/resources"

    private class Run(val exit: Int, val out: String, val err: String)

    private fun check(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = I18nCheck.run(args.toList(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return Run(exit, out.toString(Charsets.UTF_8).trimEnd(), err.toString(Charsets.UTF_8).trimEnd())
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
    fun `plural forms - the bare key stands in for the forms a group lacks, as i18next falls back on it`(@TempDir dir: Path) {
        // Crowdin's export: `ago` beside `ago_other`, never `ago_one`.
        Files.writeString(dir.resolve("en.json"), """{"ago": "{{count}} minute ago", "ago_other": "{{count}} minutes ago"}""")
        Files.writeString(dir.resolve("ru.json"), """{"ago": "{{count}} минуту назад", "ago_other": "{{count}} минут назад"}""")
        Files.writeString(dir.resolve("fr.json"), """{"ago_other": "il y a {{count}} minutes"}""")

        val run = check("--locales", dir.toString(), "--format", "json")

        val missing = JsonParser.parseString(run.out).asJsonObject["locales"].asJsonArray
            .associate { it.asJsonObject["locale"].asString to it.asJsonObject["missing"].toString() }
        assertEquals("[]", missing["en"], "the bare key covers `one`")
        assertEquals("[]", missing["ru"], "and `few` and `many`")
        assertEquals("[\"ago\",\"ago_one\"]", missing["fr"], "without the bare key, `one` is missing")
    }

    /** `en.json` and `fr.json` in [dir], holding [en] and [fr]; the check run on them with [args]. */
    private fun checkValues(dir: Path, en: String, fr: String, vararg args: String): Run {
        Files.writeString(dir.resolve("en.json"), en)
        Files.writeString(dir.resolve("fr.json"), fr)
        return check("--locales", dir.toString(), *args)
    }

    @Test
    fun `values - a placeholder missing or added is a problem`(@TempDir dir: Path) {
        val run = checkValues(
            dir,
            """{ "a": "Hi {name} {age}", "b": "Hi {name}" }""",
            """{ "a": "Salut {name}", "b": "Salut {name} {city}" }""",
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
        assertTrue("fr: 2 bad values" in run.out, run.out)
        assertTrue("  value    a  Placeholder '{age}' of the reference locale is missing" in run.out, run.out)
        assertTrue("  value    b  Placeholder '{city}' is not in the reference locale" in run.out, run.out)
    }

    @Test
    fun `values - an unbalanced brace is one problem`(@TempDir dir: Path) {
        val run = checkValues(dir, """{ "a": "Hi {name}" }""", """{ "a": "Salut {name" }""")
        assertTrue("  value    a  Unbalanced braces" in run.out, run.out)
        assertTrue("1 problem found" in run.out, run.out)
    }

    @Test
    fun `values - tags that differ from the reference`(@TempDir dir: Path) {
        val run = checkValues(dir, """{ "a": "See <1>terms</1>" }""", """{ "a": "Voir <1>les CGU <b>ici</b>" }""")
        assertTrue("Tag '</1>' of the reference locale is missing" in run.out, run.out)
        assertTrue("Tag '</b>' is not in the reference locale" in run.out, run.out)
    }

    @Test
    fun `values - an ICU plural only needs its other form`(@TempDir dir: Path) {
        val noOther = "{n, plural, one {# item}}"
        val onlyOther = "{n, plural, other {# item}}"
        val run = checkValues(
            dir,
            """{ "a": "$noOther", "b": "$onlyOther", "c": "{n, plural, =0 {no items} other {# items}}" }""",
            """{ "a": "$noOther", "b": "$onlyOther", "c": "{n, plural, =0 {aucun élément} other {# éléments}}" }""",
        )
        assertTrue("  value    a  ICU 'plural' block without the required 'other' form" in run.out, run.out)
        assertTrue(run.out.lines().none { it.startsWith("  value    b") || it.startsWith("  value    c") }, run.out)
    }

    @Test
    fun `values - a multi-line ICU message is balanced`(@TempDir dir: Path) {
        val message = "{n, plural,\\n one {x}\\n other {y}\\n}"
        val run = checkValues(dir, """{ "a": "$message" }""", """{ "a": "$message" }""")
        assertEquals(I18nCheck.OK, run.exit, run.out)
    }

    @Test
    fun `values - a plural form may spell the count out`(@TempDir dir: Path) {
        val run = checkValues(
            dir,
            """{ "item_one": "One item", "item_other": "{{count}} items" }""",
            """{ "item_one": "{{count}} objet", "item_other": "{{count}} objets" }""",
        )
        assertEquals(I18nCheck.OK, run.exit, run.out)
    }

    @Test
    fun `regional locale - inherits from its language, unless the settings turn it off`(@TempDir project: Path) {
        fun write(path: String, text: String) {
            val file = project.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        write("locales/en/common.json", """{"color": "color", "save": "Save"}""")
        write("locales/en-GB/common.json", """{"color": "colour"}""")
        write("locales/pt-BR/common.json", """{"color": "cor"}""")

        val inherited = check(project.toString(), "--locales", project.resolve("locales").toString())
        assertTrue("en-GB: no problem" in inherited.out, inherited.out)
        assertTrue("pt-BR: 1 missing" in inherited.out, inherited.out)

        write("i18n-check.json", """{"regionalFallback": false}""")
        val full = check(project.toString(), "--locales", project.resolve("locales").toString())
        assertTrue("en-GB: 1 missing" in full.out, full.out)
    }
}
