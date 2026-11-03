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

/** `i18n-check --code` on Angular's own i18n: the `@@id` the templates and `$localize` mark. */
class AngularCodeIdsCheckTest {

    private class Run(val exit: Int, val out: String, val err: String)

    private fun check(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = I18nCheck.run(args.toList(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return Run(exit, out.toString(Charsets.UTF_8).trimEnd(), err.toString(Charsets.UTF_8).trimEnd())
    }

    private fun Path.write(path: String, text: String) =
        resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }

    private fun xliff(vararg ids: String) =
        """<?xml version="1.0" encoding="UTF-8" ?>""" + "\n" +
            """<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">""" +
            """<file source-language="en-US" datatype="plaintext" original="ng2.template"><body>""" +
            ids.joinToString("\n") { """<trans-unit id="$it" datatype="html"><source>$it</source></trans-unit>""" } +
            "</body></file></xliff>\n"

    private fun Path.angularProject(vararg ids: String) {
        write(
            "angular.json",
            """{ "version": 1, "projects": { "shop": { "root": "", "i18n": { "sourceLocale": "en-US", "locales": { "fr": "src/locale/messages.fr.xlf" } } } } }""",
        )
        write("package.json", """{"dependencies": {"@angular/core": "^18.0.0", "@angular/localize": "^18.0.0"}}""")
        write("src/locale/messages.xlf", xliff(*ids))
    }

    private fun json(run: Run): JsonObject = JsonParser.parseString(run.out).asJsonObject

    private fun unused(run: Run): List<String> =
        json(run)["unused"]?.asJsonArray?.map { it.asString }.orEmpty()

    private fun unknown(run: Run): List<String> =
        json(run)["unknown"]?.asJsonArray?.map { it.asJsonObject.let { u -> "${u["file"].asString}:${u["line"].asInt} ${u["key"].asString}" } }.orEmpty()

    @Test
    fun `an id marked in a template or in localize is used, one the code no longer marks is unused`(@TempDir project: Path) {
        project.angularProject("home.title", "home.logo.alt", "cart.saved", "old.banner")
        project.write("src/app/app.component.html", "<h1 i18n=\"@@home.title\">Welcome</h1>\n<img i18n-alt=\"@@home.logo.alt\" alt=\"Logo\">")
        project.write("src/app/app.component.ts", "export class App { saved = \$localize`:@@cart.saved:Saved`; }")

        val run = check(project.toString(), "--code", "--reference", "en-US", "--format", "json")

        Assertions.assertEquals(listOf("old.banner"), unused(run), run.out + run.err)
        Assertions.assertEquals(emptyList<String>(), unknown(run), run.out)
    }

    @Test
    fun `an id the code marks but no XLIFF file holds is unknown, with its file and line`(@TempDir project: Path) {
        project.angularProject("home.title")
        project.write("src/app/app.component.html", "<h1 i18n=\"@@home.title\">Welcome</h1>\n<p i18n=\"@@home.notExtracted\">New</p>")
        project.write("src/app/app.component.ts", "export class App {\n  x = \$localize`:@@cart.notExtracted:New`;\n}")

        val run = check(project.toString(), "--code", "--reference", "en-US", "--format", "json")

        Assertions.assertEquals(
            listOf("src/app/app.component.html:2 home.notExtracted", "src/app/app.component.ts:2 cart.notExtracted"),
            unknown(run),
            run.out + run.err,
        )
    }

    @Test
    fun `while a message has no custom id, the computed ids are never unused`(@TempDir project: Path) {
        project.angularProject("home.title", "6040034932389633522", "old.banner")
        project.write("src/app/app.component.html", "<h1 i18n=\"@@home.title\">Welcome</h1>\n<footer i18n>Made with care</footer>")

        val run = check(project.toString(), "--code", "--reference", "en-US", "--format", "json")

        Assertions.assertEquals(listOf("old.banner"), unused(run), run.out + run.err)
    }

    @Test
    fun `without an extracted catalogue the markers are not read`(@TempDir project: Path) {
        project.write("package.json", """{"dependencies": {"@angular/core": "^18.0.0", "i18next": "^23.0.0"}}""")
        project.write("locales/en.json", """{"title": "Title"}""")
        project.write("src/app/app.component.html", "<h1 i18n=\"@@home.title\">Welcome</h1>\n<p>{{ t('title') }}</p>")

        val run = check(project.toString(), "--code", "--format", "json")

        Assertions.assertEquals(emptyList<String>(), unknown(run), run.out + run.err)
    }
}
