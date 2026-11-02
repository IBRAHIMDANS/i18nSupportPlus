package com.ibrahimdans.i18n.cli

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** `i18n-check` on Java resource bundles: `messages.properties`, `messages_fr.properties`. */
class PropertiesCheckTest {

    private class Run(val exit: Int, val out: String, val err: String)

    private fun check(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = I18nCheck.run(args.toList(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return Run(exit, out.toString(Charsets.UTF_8).trimEnd(), err.toString(Charsets.UTF_8).trimEnd())
    }

    private fun Path.write(path: String, text: String) =
        resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }

    private fun bundle(project: Path) {
        project.write(
            "src/main/resources/messages.properties",
            "user.name=Name\nuser.greeting=Hello {0}\nfarewell=Goodbye\n",
        )
        project.write(
            "src/main/resources/messages_fr.properties",
            "user.name=Nom\nuser.greeting=Bonjour {1}\nfarewell=\nold=Ancien\n",
        )
        project.write("src/main/resources/messages_pt_BR.properties", "user.name=Nome\\u00e7\nuser.greeting=Ol\\u00e1 {0}\nfarewell=Tchau\n")
        // Configuration, not a bundle: no localized sibling.
        project.write("src/main/resources/application.properties", "server.port=8080\n")
        // Maven's copy of the resources: never a source.
        project.write("target/classes/messages_fr.properties", "broken=\n")
    }

    @Test
    fun `project mode - each bundle file is its locale, missing, orphan, empty and placeholder reported`(@TempDir project: Path) {
        bundle(project)

        val run = check(project.toString(), "--format", "json")

        Assertions.assertEquals(I18nCheck.PROBLEMS, run.exit, run.err)
        val report = JsonParser.parseString(run.out).asJsonObject
        Assertions.assertEquals("en", report["reference"].asString)
        val locales = report["locales"].asJsonArray.map { it.asJsonObject }.associateBy { it["locale"].asString }
        Assertions.assertEquals(setOf("en", "fr", "pt_BR"), locales.keys)
        val fr = locales.getValue("fr")
        Assertions.assertEquals("[]", fr["missing"].toString())
        Assertions.assertEquals("[\"old\"]", fr["orphans"].toString())
        Assertions.assertEquals("[\"farewell\"]", fr["empty"].toString())
        Assertions.assertTrue(fr["values"].toString().contains("{0}"), fr["values"].toString())
        val ptBr = locales.getValue("pt_BR")
        Assertions.assertEquals("[]", ptBr["missing"].toString())
        Assertions.assertEquals("[]", ptBr["values"].toString())
    }

    @Test
    fun `example project - the problems its README lists`() {
        val run = check("../examples/spring-messages")

        Assertions.assertEquals(I18nCheck.PROBLEMS, run.exit, run.err)
        Assertions.assertTrue(run.out.contains("pt_BR: 1 missing"), run.out)
        Assertions.assertTrue(run.out.contains("missing  cart.items"), run.out)
        Assertions.assertTrue(run.out.contains("empty    farewell"), run.out)
        Assertions.assertTrue(run.out.contains("{0}"), run.out)
        Assertions.assertFalse(run.out.contains("spring.application.name"), run.out)
    }

    @Test
    fun `locales mode - a folder of messages files`(@TempDir project: Path) {
        bundle(project)

        val run = check("--locales", project.resolve("src/main/resources").toString())

        Assertions.assertEquals(I18nCheck.PROBLEMS, run.exit, run.err)
        Assertions.assertTrue(run.out.startsWith("Reference locale: en (3 locales)"), run.out)
        Assertions.assertTrue(run.out.contains("orphan   old"), run.out)
        Assertions.assertFalse(run.out.contains("server.port"), run.out)
    }
}
