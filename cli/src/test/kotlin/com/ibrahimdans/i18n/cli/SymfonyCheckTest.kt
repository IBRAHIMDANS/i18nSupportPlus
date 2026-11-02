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

/** `i18n-check` on Symfony's catalogues: `translations/<domain>[+intl-icu].<locale>.<format>`. */
class SymfonyCheckTest {

    private class Run(val exit: Int, val out: String, val err: String)

    private fun check(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = I18nCheck.run(args.toList(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return Run(exit, out.toString(Charsets.UTF_8).trimEnd(), err.toString(Charsets.UTF_8).trimEnd())
    }

    private fun Path.write(path: String, text: String) =
        resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }

    private fun Path.symfony() = write("composer.json", """{"require": {"symfony/translation": "7.1.*"}}""")

    private fun xliff12(locale: String, vararg units: String) =
        """<?xml version="1.0" encoding="utf-8"?>""" + "\n" +
            """<xliff xmlns="urn:oasis:names:tc:xliff:document:1.2" version="1.2">""" +
            """<file source-language="en" target-language="$locale" datatype="plaintext" original="file.ext"><body>""" +
            units.joinToString("\n") +
            "</body></file></xliff>\n"

    private fun locales(run: Run): Map<String, JsonObject> =
        JsonParser.parseString(run.out).asJsonObject["locales"].asJsonArray.map { it.asJsonObject }.associateBy { it["locale"].asString }

    @Test
    fun `YAML - a key nested in one locale and written whole in another is the same key, the domain its namespace`(@TempDir project: Path) {
        project.symfony()
        project.write("translations/messages.en.yaml", "user:\n  name: Name\n  email: Email\nfarewell: Goodbye\n")
        project.write("translations/messages.fr.yaml", "user.name: Nom\nfarewell: ''\nold: Ancien\n")
        project.write("translations/admin.en.yaml", "dashboard:\n  title: Dashboard\n")
        project.write("translations/admin.fr.yaml", "dashboard:\n  title: Tableau de bord\n")

        val run = check(project.toString(), "--format", "json")

        Assertions.assertEquals(I18nCheck.PROBLEMS, run.exit, run.err)
        val fr = locales(run).getValue("fr")
        Assertions.assertEquals("[\"messages:user.email\"]", fr["missing"].toString())
        Assertions.assertEquals("[\"messages:old\"]", fr["orphans"].toString())
        Assertions.assertEquals("[\"messages:farewell\"]", fr["empty"].toString())
        Assertions.assertFalse(run.out.contains("dashboard"), run.out)
    }

    @Test
    fun `XLIFF 1·2 and 2·0 - keyed by resname or name, else by the source`(@TempDir project: Path) {
        project.symfony()
        project.write(
            "translations/validators.en.xlf",
            xliff12(
                "en",
                """<trans-unit id="a" resname="email.invalid"><source>email.invalid</source><target>Invalid e-mail.</target></trans-unit>""",
                """<trans-unit id="b"><source>This value is too short.</source></trans-unit>""",
            ),
        )
        project.write(
            "translations/validators.fr.xlf",
            """
                <xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="fr">
                  <file id="validators.fr">
                    <unit id="a" name="email.invalid"><segment><source>email.invalid</source><target>E-mail invalide.</target></segment></unit>
                  </file>
                </xliff>
            """.trimIndent(),
        )

        val run = check(project.toString(), "--format", "json")

        Assertions.assertEquals(I18nCheck.PROBLEMS, run.exit, run.err)
        val fr = locales(run).getValue("fr")
        Assertions.assertEquals("[\"validators:This value is too short.\"]", fr["missing"].toString())
        Assertions.assertEquals("[]", fr["orphans"].toString())
    }

    @Test
    fun `an ICU catalogue is read in its domain`(@TempDir project: Path) {
        project.symfony()
        project.write("translations/messages.en.yaml", "title: Title\n")
        project.write("translations/messages+intl-icu.en.yaml", "cart.items: '{count, plural, one {# item} other {# items}}'\n")
        project.write("translations/messages.fr.yaml", "title: Titre\n")

        val run = check(project.toString(), "--format", "json")

        Assertions.assertEquals(I18nCheck.PROBLEMS, run.exit, run.err)
        Assertions.assertEquals("[\"messages:cart.items\"]", locales(run).getValue("fr")["missing"].toString())
    }

    @Test
    fun `without symfony translation in composer json, the catalogues are no sources`(@TempDir project: Path) {
        project.write("composer.json", """{"require": {"php": ">=8.2"}}""")
        project.write("translations/messages.en.yaml", "title: Title\n")
        project.write("translations/messages.fr.yaml", "other: Autre\n")

        val run = check(project.toString())

        Assertions.assertFalse(run.out.contains("other"), run.out)
    }

    @Test
    fun `example project - the key left untranslated on purpose is reported`() {
        val run = check("../examples/symfony")

        Assertions.assertEquals(I18nCheck.PROBLEMS, run.exit, run.err)
        Assertions.assertTrue(run.out.contains("only_in_english"), run.out)
        Assertions.assertFalse(run.out.contains("footer.copyright"), run.out)
        Assertions.assertFalse(run.out.contains("cart.items"), run.out)
        Assertions.assertFalse(run.out.contains("contact.email.invalid"), run.out)
    }
}
