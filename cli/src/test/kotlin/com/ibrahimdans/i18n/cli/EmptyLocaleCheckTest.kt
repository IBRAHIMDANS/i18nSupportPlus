package com.ibrahimdans.i18n.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** A locale whose files hold no value is compared as any other: every key missing, not left out of the report. */
class EmptyLocaleCheckTest {

    private class Run(val exit: Int, val out: String, val err: String)

    private fun check(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = I18nCheck.run(args.toList(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return Run(exit, out.toString(Charsets.UTF_8).trimEnd(), err.toString(Charsets.UTF_8).trimEnd())
    }

    private fun Path.write(path: String, text: String) =
        resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }

    private fun locales(run: Run): Map<String, JsonObject> =
        JsonParser.parseString(run.out).asJsonObject["locales"].asJsonArray.map { it.asJsonObject }.associateBy { it["locale"].asString }

    @Test
    fun `an empty locale file has every key missing and fails the check, in a locales directory`(@TempDir dir: Path) {
        dir.write("en.json", """{ "menu": { "home": "Home" }, "save": "Save" }""")
        dir.write("fr.json", "{}")

        val run = check("--locales", dir.toString(), "--format", "json")

        Assertions.assertEquals(I18nCheck.PROBLEMS, run.exit, run.out)
        Assertions.assertEquals("[\"menu.home\",\"save\"]", locales(run).getValue("fr")["missing"].toString())
    }

    @Test
    fun `an empty locale file has every key missing in a whole project too`(@TempDir project: Path) {
        project.write("locales/en.json", """{ "save": "Save" }""")
        project.write("locales/de.json", "{}")

        val run = check(project.toString(), "--format", "json")

        Assertions.assertEquals(I18nCheck.PROBLEMS, run.exit, run.out)
        Assertions.assertEquals("[\"save\"]", locales(run).getValue("de")["missing"].toString())
    }

    @Test
    fun `plural forms an empty locale needs are its own language's`(@TempDir dir: Path) {
        dir.write("en.json", """{ "item_one": "{{count}} item", "item_other": "{{count}} items" }""")
        dir.write("ru.json", "{}")

        val run = check("--locales", dir.toString(), "--format", "json")

        Assertions.assertEquals(
            "[\"item_few\",\"item_many\",\"item_one\",\"item_other\"]",
            locales(run).getValue("ru")["missing"].toString(),
        )
    }

    @Test
    fun `an Angular XLIFF copied without any target has every id missing`(@TempDir project: Path) {
        project.write(
            "angular.json",
            """{ "version": 1, "projects": { "shop": { "root": "", "i18n": { "sourceLocale": "en-US", "locales": { "de": "src/locale/messages.de.xlf" } } } } }""",
        )
        fun xliff(vararg units: String) =
            """<?xml version="1.0" encoding="UTF-8" ?>""" + "\n" +
                """<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">""" +
                """<file source-language="en-US" datatype="plaintext" original="ng2.template"><body>""" +
                units.joinToString("\n") + "</body></file></xliff>\n"
        project.write("src/locale/messages.xlf", xliff("""<trans-unit id="home.title"><source>Welcome</source></trans-unit>"""))
        project.write(
            "src/locale/messages.de.xlf",
            xliff("""<trans-unit id="home.title"><source>Welcome</source></trans-unit>"""),
        )

        val run = check(project.toString(), "--format", "json")

        Assertions.assertEquals(I18nCheck.PROBLEMS, run.exit, run.err)
        Assertions.assertEquals("[\"home.title\"]", locales(run).getValue("de")["missing"].toString())
    }

    @Test
    fun `an empty ignored locale stays out of the report`(@TempDir dir: Path) {
        dir.write("i18n-check.json", """{ "ignoredLocales": "fr" }""")
        dir.write("locales/en.json", """{ "save": "Save" }""")
        dir.write("locales/fr.json", "{}")

        val run = check(dir.toString(), "--format", "json")

        Assertions.assertEquals(I18nCheck.OK, run.exit, run.out)
        Assertions.assertFalse("fr" in locales(run), run.out)
    }

    @Test
    fun `an empty regional locale inherits every key from its language`(@TempDir dir: Path) {
        dir.write("en.json", """{ "save": "Save" }""")
        dir.write("en-GB.json", "{}")

        val run = check("--locales", dir.toString(), "--format", "json")

        Assertions.assertEquals(I18nCheck.OK, run.exit, run.out)
        Assertions.assertEquals("[]", locales(run).getValue("en-GB")["missing"].toString())
    }
}
