package com.ibrahimdans.i18n.cli

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** `--code` reads no Java nor Dart: the keys of a `.properties` bundle or an ARB catalogue are never unused. */
class CodeUnreadFormatsTest {

    private fun unused(project: Path): List<String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        I18nCheck.run(
            listOf(project.toString(), "--code", "--format", "json"),
            PrintStream(out, true, Charsets.UTF_8),
            PrintStream(err, true, Charsets.UTF_8),
        )
        val report = JsonParser.parseString(out.toString(Charsets.UTF_8)).asJsonObject
        return report["unused"]?.asJsonArray?.map { it.asString } ?: error(err.toString(Charsets.UTF_8))
    }

    private fun Path.write(path: String, text: String) =
        resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }

    /** A web front reading `title`, its JSON catalogues holding `title` and the unused `stale`. */
    private fun webFront(project: Path) {
        project.write("web/src/app.ts", "t('title')\n")
        project.write("web/locales/en.json", """{"title": "Shop", "stale": "Old"}""")
        project.write("web/locales/fr.json", """{"title": "Boutique", "stale": "Vieux"}""")
    }

    @Test
    fun `a bundle's keys are not unused, a JSON key the code leaves alone still is`(@TempDir project: Path) {
        webFront(project)
        project.write("api/src/main/resources/messages.properties", "mail.subject=Welcome\n")
        project.write("api/src/main/resources/messages_fr.properties", "mail.subject=Bienvenue\n")

        Assertions.assertEquals(listOf("stale"), unused(project))
    }

    @Test
    fun `an ARB catalogue's keys are not unused`(@TempDir project: Path) {
        webFront(project)
        project.write("app/lib/l10n/app_en.arb", """{"@@locale": "en", "hello": "Hello", "@hello": {}}""")
        project.write("app/lib/l10n/app_fr.arb", """{"@@locale": "fr", "hello": "Bonjour"}""")

        Assertions.assertEquals(listOf("stale"), unused(project))
    }

    @Test
    fun `a key a JSON file holds too is still checked`(@TempDir project: Path) {
        webFront(project)
        project.write("api/src/main/resources/messages.properties", "stale=Old\n")
        project.write("api/src/main/resources/messages_fr.properties", "stale=Vieux\n")

        Assertions.assertEquals(listOf("stale"), unused(project))
    }
}
