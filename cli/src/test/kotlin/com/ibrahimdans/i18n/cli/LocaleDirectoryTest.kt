package com.ibrahimdans.i18n.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** The files of a `--locales` directory, one format per case ([LocaleDirectory]), read through the command. */
class LocaleDirectoryTest {

    private val resources = "src/test/resources"

    private class Run(val exit: Int, val out: String, val err: String)

    private fun check(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = I18nCheck.run(args.toList(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return Run(exit, out.toString(Charsets.UTF_8).trimEnd(), err.toString(Charsets.UTF_8).trimEnd())
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
    fun `TS and JS catalogues - read, the code file beside them left out`() {
        val run = check("--locales", "$resources/ts")
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
    fun `PO catalogues - msgctxt keys apart, a plural form left blank is empty, header and obsolete entries skipped`() {
        val run = check("--locales", "$resources/po")
        assertEquals(
            """
            Reference locale: en (2 locales)

            en: no problem
            fr: 1 missing, 1 orphan, 2 empty
              missing  Open
              orphan   Legacy
              empty    Open (msgctxt: menu)
              empty    {count} item

            4 problems found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `Laravel PHP arrays - nested arrays are nested keys, the file names the namespace`() {
        val run = check("--locales", "$resources/php")
        assertEquals(
            """
            Reference locale: en (2 locales)

            en: no problem
            fr: 1 missing, 1 orphan, 1 empty
              missing  messages:auth.throttle
              orphan   messages:legacy
              empty    messages:welcome

            3 problems found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `ARB catalogues - locale from @@locale or the name, metadata no key`() {
        val run = check("--locales", "$resources/arb")
        assertEquals(
            """
            Reference locale: en (3 locales)

            de: no problem
            en: no problem
            fr: 1 missing, 1 orphan, 1 empty
              missing  title
              orphan   old
              empty    farewell

            3 problems found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }
}
