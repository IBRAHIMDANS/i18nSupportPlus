package com.ibrahimdans.i18n.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class GithubReportTest {

    private class Run(val exit: Int, val out: String) {
        /** The workflow commands, before the blank line that opens the text report. */
        val commands get() = out.lines().takeWhile { it.isNotEmpty() }
    }

    private fun check(vararg args: String, env: Map<String, String> = emptyMap()): Run {
        val out = ByteArrayOutputStream()
        val exit = I18nCheck.run(args.toList(), PrintStream(out, true, Charsets.UTF_8), PrintStream(ByteArrayOutputStream()), env::get)
        return Run(exit, out.toString(Charsets.UTF_8).trimEnd())
    }

    /** [path] as the annotations give it: from the working directory, GitHub's workspace. */
    private fun fromWorkspace(path: Path) = Path.of("").toAbsolutePath().relativize(path.toAbsolutePath().normalize()).joinToString("/")

    /**
     * `fr` lacks `home.title` and holds `home.old`, which `en` lacks; the code calls `home.gone`, which `en`
     * lacks too, on line 4 of `src/App.tsx`.
     */
    private fun project(dir: Path): Path {
        Files.createDirectories(dir.resolve("locales"))
        Files.writeString(dir.resolve("locales/en.json"), """{ "home": { "title": "Home" } }""")
        Files.writeString(dir.resolve("locales/fr.json"), """{ "home": { "old": "Ancien" } }""")
        Files.createDirectories(dir.resolve("src"))
        Files.writeString(
            dir.resolve("src/App.tsx"),
            """
            import { useTranslation } from 'react-i18next';
            export const App = () => {
              const { t } = useTranslation();
              return t('home.title') + t('home.gone');
            };
            """.trimIndent(),
        )
        return dir
    }

    @Test
    fun `github format - an unknown key on its line, a missing key on its locale's file, then the text report`(@TempDir dir: Path) {
        val project = project(dir)
        val run = check(project.toString(), "--code", "--format", "github")
        assertEquals(
            listOf(
                "::error file=${fromWorkspace(project.resolve("locales/fr.json"))},line=1,title=Missing translation::fr lacks home.title, which the reference locale en holds",
                "::error file=${fromWorkspace(project.resolve("locales/fr.json"))},line=1,title=Orphan key::fr holds home.old, which the reference locale en lacks",
                "::error file=${fromWorkspace(project.resolve("src/App.tsx"))},line=4,title=Unknown key::home.gone is called here, but the reference locale en has no such key",
            ),
            run.commands,
        )
        assertTrue(run.out.endsWith("3 problems found (allowed: 0)."), run.out)
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `github format - within --max-problems the annotations are warnings and the check passes`(@TempDir dir: Path) {
        val run = check(project(dir).toString(), "--code", "--format", "github", "--max-problems", "3")
        assertEquals(3, run.commands.size)
        assertTrue(run.commands.all { it.startsWith("::warning file=") }, run.commands.joinToString("\n"))
        assertEquals(I18nCheck.OK, run.exit)
    }

    @Test
    fun `github format - each key annotated on the file of its namespace`() {
        val locales = Path.of("../examples/react-multi-namespace/public/locales")
        val run = check("--locales", locales.toString(), "--format", "github")
        val common = fromWorkspace(locales.resolve("fr/common.json"))
        val navigation = fromWorkspace(locales.resolve("fr/navigation.json"))
        assertEquals(
            listOf(
                "::error file=$common,line=1,title=Missing translation::fr lacks common:actions.eddit, which the reference locale en holds",
                "::error file=$common,line=1,title=Missing translation::fr lacks common:actions.saxve, which the reference locale en holds",
                "::error file=$navigation,line=1,title=Empty translation::navigation:breadrumb.home is empty in fr",
            ),
            run.commands,
        )
    }

    @Test
    fun `github format - the step summary gets a table per locale when GITHUB_STEP_SUMMARY is set`(@TempDir dir: Path) {
        val summary = dir.resolve("summary.md")
        Files.writeString(summary, "earlier step\n")
        check(project(dir.resolve("app")).toString(), "--code", "--format", "github", env = mapOf(GithubReport.STEP_SUMMARY to summary.toString()))
        assertEquals(
            """
            earlier step
            ### i18n-check

            | Locale | Missing | Orphans | Empty | Bad values |
            | --- | ---: | ---: | ---: | ---: |
            | en (reference) | 0 | 0 | 0 | 0 |
            | fr | 1 | 1 | 0 | 0 |

            Unused keys: 0

            Unknown keys: 1

            3 problems found (allowed: 0).

            """.trimIndent(),
            Files.readString(summary),
        )
    }

    @Test
    fun `other formats - no step summary written`(@TempDir dir: Path) {
        val summary = dir.resolve("summary.md")
        check(project(dir.resolve("app")).toString(), "--code", env = mapOf(GithubReport.STEP_SUMMARY to summary.toString()))
        assertFalse(Files.exists(summary))
    }

    @Test
    fun `escaping - as @actions core escapes a command's message and properties`() {
        assertEquals("50%25 done%0Anext", GithubReport.escapeData("50% done\nnext"))
        assertEquals("a%3Ab%2Cc%0D", GithubReport.escapeProperty("a:b,c\r"))
    }

    @Test
    fun `a bad value is annotated on the locale's file and counted in the summary`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("en.json"), """{ "a": "Hi {name} {age}" }""")
        Files.writeString(dir.resolve("fr.json"), """{ "a": "Salut {name}" }""")
        val summary = dir.resolve("summary.md")

        val run = check("--locales", dir.toString(), "--format", "github", env = mapOf(GithubReport.STEP_SUMMARY to summary.toString()))

        assertEquals(
            "::error file=${fromWorkspace(dir.resolve("fr.json"))},line=1,title=Bad translation value::" +
                "a in fr: Placeholder '{age}' of the reference locale is missing",
            run.commands.single(),
        )
        assertTrue("| fr | 0 | 0 | 0 | 1 |" in Files.readString(summary))
    }
}
