package com.ibrahimdans.i18n.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** A catalogue the CLI cannot read stops the check, named ([CheckFormats.unreadable]), through the command. */
class CheckFormatsTest {

    private val resources = "src/test/resources"

    private class Run(val exit: Int, val out: String, val err: String)

    private fun check(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = I18nCheck.run(args.toList(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return Run(exit, out.toString(Charsets.UTF_8).trimEnd(), err.toString(Charsets.UTF_8).trimEnd())
    }

    @Test
    fun `TS catalogue with a spread - not checked, named with the line, exit 2`() {
        val run = check("--locales", "$resources/ts-partial")
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertTrue(run.err.contains("ts-partial/fr.ts: its catalogue is not written as plain literals (spread at line 4)"), run.err)
    }

    @Test
    fun `Laravel PHP array built by code - not checked, named with the line, exit 2`() {
        val run = check("--locales", "$resources/php-partial")
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertTrue(run.err.contains("php-partial/en/messages.php: its catalogue is not written as plain literals (concatenation at line 5)"), run.err)
    }

    @Test
    fun `invalid translation file - named, exit 2`() {
        val run = check("--locales", "$resources/invalid")
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertTrue(run.err.contains("not a valid JSON translation file"), run.err)
    }
}
