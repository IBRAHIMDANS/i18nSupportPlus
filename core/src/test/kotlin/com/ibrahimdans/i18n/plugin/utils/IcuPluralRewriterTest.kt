package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.utils.IcuPluralRewriter.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IcuPluralRewriterTest {

    private val files = "{count, plural, one {# file} other {# files}}"

    private fun rewritten(message: String, locale: String): Result.Rewritten =
        IcuPluralRewriter.forLocale(message, locale) as Result.Rewritten

    @Test
    fun `Russian gets few and many, copied from other`() {
        val result = rewritten(files, "ru")

        assertEquals("{count, plural, one {# file} few {# files} many {# files} other {# files}}", result.text)
        assertEquals(setOf("few", "many"), result.copied)
    }

    @Test
    fun `Japanese keeps other alone`() {
        val result = rewritten(files, "ja")

        assertEquals("{count, plural, other {# files}}", result.text)
        assertEquals(emptySet<String>(), result.copied)
    }

    @Test
    fun `a language with the same categories gets the message back, nothing to review`() {
        val result = rewritten(files, "fr")

        assertEquals(files, result.text)
        assertEquals(emptySet<String>(), result.copied)
    }

    @Test
    fun `exact branches and the offset are kept, the text around the block too`() {
        val result = rewritten("You and {n, plural, offset:1 =0 {nobody} =1 {one friend} one {# friend} other {# friends}} came.", "pl")

        assertEquals(
            "You and {n, plural, offset:1 =0 {nobody} =1 {one friend} one {# friend} few {# friends} many {# friends} other {# friends}} came.",
            result.text
        )
    }

    @Test
    fun `a plural inside a select branch is rewritten, the select kept branch for branch`() {
        val message = "{gender, select, female {{count, plural, one {her # file} other {her # files}}} other {{count, plural, one {# file} other {# files}}}}"

        val result = rewritten(message, "uk")

        assertEquals(
            "{gender, select, female {{count, plural, one {her # file} few {her # files} many {her # files} other {her # files}}} " +
                "other {{count, plural, one {# file} few {# files} many {# files} other {# files}}}}",
            result.text
        )
    }

    @Test
    fun `selectordinal and variables are kept as written`() {
        val message = "{name}: {n, selectordinal, one {#st} two {#nd} few {#rd} other {#th}} of {total, number}"

        assertEquals(message, rewritten(message, "ru").text)
    }

    @Test
    fun `quoted braces are text, not syntax`() {
        val result = rewritten("{count, plural, one {'{'# file'}'} other {# files}}", "cs")

        assertEquals("{count, plural, one {'{'# file'}'} few {# files} other {# files}}", result.text)
    }

    @Test
    fun `two levels of nesting, a plural without other, unbalanced braces are left to the user`() {
        val deep = "{a, select, x {{b, select, y {{count, plural, one {#} other {#}}} other {z}}} other {w}}"

        assertEquals(Result.Unsupported, IcuPluralRewriter.forLocale(deep, "ru"))
        assertEquals(Result.Unsupported, IcuPluralRewriter.forLocale("{count, plural, one {# file}}", "ru"))
        assertEquals(Result.Unsupported, IcuPluralRewriter.forLocale("{count, plural, one {# file} other {# files}", "ru"))
    }

    @Test
    fun `a value is checked against the categories its language needs`() {
        assertEquals(emptyList<String>(), IcuPluralRewriter.mismatches(rewritten(files, "ru").text, "ru"))
        assertEquals(
            listOf("'count' needs one, few, many, other; has one, other"),
            IcuPluralRewriter.mismatches(files, "ru")
        )
        assertEquals(listOf("'count' needs other; has one, other"), IcuPluralRewriter.mismatches(files, "ja"))
        assertEquals(emptyList<String>(), IcuPluralRewriter.mismatches("{n, plural, =0 {none} one {#} other {#}}", "en"))
        assertNull(IcuPluralRewriter.mismatches("{count, plural, one {#} other {#}", "en"))
    }
}
