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

/** `i18n-check` on Angular's own i18n: the XLIFF files `angular.json` names. */
class AngularXliffCheckTest {

    private class Run(val exit: Int, val out: String, val err: String)

    private fun check(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = I18nCheck.run(args.toList(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return Run(exit, out.toString(Charsets.UTF_8).trimEnd(), err.toString(Charsets.UTF_8).trimEnd())
    }

    private fun Path.write(path: String, text: String) =
        resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }

    private fun Path.angular(locales: String = """"fr": "src/locale/messages.fr.xlf"""") = write(
        "angular.json",
        """{ "version": 1, "projects": { "shop": { "root": "", "i18n": { "sourceLocale": "en-US", "locales": { $locales } } } } }""",
    )

    private fun xliff(vararg units: String) =
        """<?xml version="1.0" encoding="UTF-8" ?>""" + "\n" +
            """<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">""" +
            """<file source-language="en-US" datatype="plaintext" original="ng2.template"><body>""" +
            units.joinToString("\n") +
            "</body></file></xliff>\n"

    private fun unit(id: String, source: String, target: String? = null) =
        """<trans-unit id="$id" datatype="html"><source>$source</source>""" +
            target?.let { "<target>$it</target>" }.orEmpty() + "</trans-unit>"

    private fun locales(run: Run): Map<String, JsonObject> =
        JsonParser.parseString(run.out).asJsonObject["locales"].asJsonArray.map { it.asJsonObject }.associateBy { it["locale"].asString }

    @Test
    fun `an id missing from a locale, or copied without a target, is missing there, and one it alone holds is an orphan`(@TempDir project: Path) {
        project.angular()
        project.write("src/locale/messages.xlf", xliff(unit("home.title", "Welcome"), unit("home.intro", "Hello"), unit("cart.terms", "Terms")))
        project.write(
            "src/locale/messages.fr.xlf",
            xliff(unit("home.title", "Welcome", "Bienvenue"), unit("cart.terms", "Terms"), unit("old.id", "Old", "Ancien")),
        )

        val run = check(project.toString(), "--format", "json")

        Assertions.assertEquals(I18nCheck.PROBLEMS, run.exit, run.err)
        val fr = locales(run).getValue("fr")
        Assertions.assertEquals("[\"cart.terms\",\"home.intro\"]", fr["missing"].toString())
        Assertions.assertEquals("[\"old.id\"]", fr["orphans"].toString())
        Assertions.assertTrue("en-US" in locales(run), run.out)
    }

    @Test
    fun `a locale's files as an object or an array are read, and a file not extracted yet is skipped`(@TempDir project: Path) {
        project.angular(""""fr": { "translation": "src/locale/messages.fr.xlf" }, "de": ["src/locale/messages.de.xlf"], "es": "src/locale/messages.es.xlf"""")
        project.write("src/locale/messages.xlf", xliff(unit("title", "Title"), unit("subtitle", "Subtitle")))
        project.write("src/locale/messages.fr.xlf", xliff(unit("title", "Title", "Titre"), unit("subtitle", "Subtitle", "Sous-titre")))
        project.write("src/locale/messages.de.xlf", xliff(unit("title", "Title", "Titel"), unit("subtitle", "Subtitle")))

        val run = check(project.toString(), "--format", "json")

        Assertions.assertEquals(I18nCheck.PROBLEMS, run.exit, run.err)
        Assertions.assertEquals("[]", locales(run).getValue("fr")["missing"].toString())
        Assertions.assertEquals("[\"subtitle\"]", locales(run).getValue("de")["missing"].toString())
        Assertions.assertFalse("es" in locales(run), run.out)
    }

    @Test
    fun `with --code the ids are never reported unused - the i18n markers are not read`(@TempDir project: Path) {
        project.angular()
        project.write("package.json", """{"dependencies": {"@angular/core": "^18.0.0", "@angular/localize": "^18.0.0"}}""")
        project.write("src/app/app.component.html", """<h1 i18n="@@home.title">Welcome</h1>""")
        project.write("src/locale/messages.xlf", xliff(unit("home.title", "Welcome")))
        project.write("src/locale/messages.fr.xlf", xliff(unit("home.title", "Welcome", "Bienvenue")))

        val run = check(project.toString(), "--code", "--format", "json")

        Assertions.assertEquals(I18nCheck.OK, run.exit, run.out + run.err)
    }

    @Test
    fun `a locale file that is not XML stops the check`(@TempDir project: Path) {
        project.angular()
        project.write("src/locale/messages.xlf", xliff(unit("title", "Title")))
        project.write("src/locale/messages.fr.xlf", "<xliff><file>")

        val run = check(project.toString())

        Assertions.assertEquals(I18nCheck.CANNOT_CHECK, run.exit, run.out)
        Assertions.assertTrue(run.err.contains("messages.fr.xlf"), run.err)
    }

    @Test
    fun `example project - the ids left untranslated on purpose are reported`() {
        val run = check("../examples/angular-native-i18n")

        Assertions.assertEquals(I18nCheck.PROBLEMS, run.exit, run.err)
        Assertions.assertTrue(run.out.contains("cart.terms"), run.out)
        Assertions.assertTrue(run.out.contains("home.intro"), run.out)
        Assertions.assertTrue(run.out.contains("fr: no problem"), run.out)
    }
}
