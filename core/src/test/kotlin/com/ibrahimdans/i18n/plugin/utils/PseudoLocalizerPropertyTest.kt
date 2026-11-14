package com.ibrahimdans.i18n.plugin.utils

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Messages mixing readable text with what [PseudoLocalizer] promises to keep: `{{name}}`,
 * `{{- name}}`, `%{name}`, `{name}`, `%s` / `%1$s`, tags, HTML entities, `{amount, number}` and
 * ICU `plural` / `select` / `selectordinal` blocks, nested.
 *
 * Each case is built twice: as the message, and as the output the KDoc describes — the text
 * accented, everything else verbatim, `[` … ` ···]` around with one dot per 30 % of the letters.
 * The text never holds `{`, `}`, `%`, `<`, `>` or `&`: next to a placeholder they would form a
 * token of their own, which the KDoc leaves to the reader. i18next nesting (`$t(key)`, options
 * included) and vue-i18n linked messages (`@:key`, `@.lower:key`, `@:{'key'}`, `@:(key)`) are kept
 * tokens too; a bare linked key is followed by a space, where vue-i18n ends it. The seed is fixed:
 * a failure replays the same cases.
 */
class PseudoLocalizerPropertyTest {

    @OptIn(ExperimentalKotest::class)
    private val config = PropTestConfig(seed = 20261114L, iterations = 500)

    private val accents: Map<Char, Char> = (
        "abcdefghijklmnopqrstuvwxyz".zip("áƀçđéƒĝĥîĵķļɱñöþǫŕšţûṽŵẋýž") +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ".zip("ÅƁÇĐÉƑĜĤÎĴĶĻṀÑÖÞǪŔŠŢÛṼŴẊÝŽ")
        ).toMap()

    private val textChars = listOf("a", "z", "M", "Q", "x", "0", "7", " ", ".", ",", "!", "?", "'", "-", "#", ":", "é", "日", "😀")
        .flatMap { s -> s.codePoints().toArray().map(::Codepoint) }

    private val words = Arb.string(1..8, Arb.of(textChars))

    private val keptTokens = listOf(
        "{{name}}", "{{- raw}}", "%{count}", "{user}", "%s", "%d", "%i", "%f", "%1\$s", "%2\$d",
        "<1>", "</1>", "<b>", "</b>", "<br/>", "<br />", "<a href='/x'>", "</a>", "&nbsp;", "&#39;", "&amp;",
        "{amount, number}", "{when, date, short}",
        "\$t(common:save)", "\$t(key, {\"count\": {{count}} })", "\$t(other, {\"label\": \"(a) b\"})",
        "@:common.save ", "@.lower:message.home ", "@.capitalize:{'day'}", "@:(a key)",
    )

    /** One part of a message: the text as written, the text expected out, its letter count. */
    private data class Part(val source: String, val expected: String, val letters: Int)

    private fun text(word: String) = Part(word, word.map { accents[it] ?: it }.joinToString(""), word.count(Char::isLetter))

    private fun kept(token: String) = Part(token, token, 0)

    private fun join(parts: List<Part>) =
        Part(parts.joinToString("") { it.source }, parts.joinToString("") { it.expected }, parts.sumOf { it.letters })

    /** A sequence of text, kept tokens and, while [depth] allows, ICU blocks. */
    private fun body(depth: Int): Arb<Part> = arbitrary {
        val count = Arb.int(1..5).bind()
        join((1..count).map {
            when (Arb.int(0..9).bind()) {
                in 0..4 -> text(words.bind())
                in 5..7 -> kept(Arb.of(keptTokens).bind())
                else -> if (depth > 0) icu(depth - 1).bind() else text(words.bind())
            }
        })
    }

    private val selectors = mapOf(
        "plural" to listOf("zero", "one", "two", "few", "many", "other", "=0", "=1"),
        "selectordinal" to listOf("one", "two", "few", "other"),
        "select" to listOf("male", "female", "other"),
    )

    /** `{n, plural, one {…} other {…}}`: argument, keyword and selectors kept, branches accented. */
    private fun icu(depth: Int): Arb<Part> = arbitrary {
        val type = Arb.of(selectors.keys.toList()).bind()
        val head = Arb.of("{n, $type,", "{count,$type,", "{ gender , $type ,").bind()
        val offset = if (type == "plural" && Arb.int(0..3).bind() == 0) " offset:1" else ""
        val chosen = selectors.getValue(type).shuffled(kotlin.random.Random(Arb.int(0..1000).bind()))
            .take(Arb.int(1..3).bind()).let { if ("other" in it) it else it + "other" }
        val branches = chosen.map { selector ->
            val branch = if (type == "select") body(depth).bind() else join(listOf(kept("#"), text(" "), body(depth).bind()))
            Part(" $selector {${branch.source}}", " $selector {${branch.expected}}", branch.letters)
        }
        val parts = listOf(kept("$head$offset")) + branches + kept("}")
        join(parts)
    }

    private val messages = body(depth = 2)

    private fun expectedOf(part: Part): String {
        if (part.source.isBlank()) return part.source
        val dots = maxOf(1, Math.ceil(part.letters * 0.3).toInt())
        return "[${part.expected} ${"·".repeat(dots)}]"
    }

    @Test
    fun `text is accented and padded, every placeholder, tag and ICU syntax kept as is`() {
        runBlocking {
            checkAll(config, messages) { message ->
                assertEquals(expectedOf(message), PseudoLocalizer.localize(message.source), message.source)
            }
        }
    }

    @Test
    fun `the kept tokens come out in the same order`() {
        runBlocking {
            val token = Regex(keptTokens.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })
            checkAll(config, messages) { message ->
                val out = PseudoLocalizer.localize(message.source)
                assertEquals(token.findAll(message.source).map { it.value }.toList(), token.findAll(out).map { it.value }.toList(), message.source)
            }
        }
    }

    @Test
    fun `the same message always gives the same result, and its braces stay balanced`() {
        runBlocking {
            checkAll(config, messages) { message ->
                val out = PseudoLocalizer.localize(message.source)
                assertEquals(out, PseudoLocalizer.localize(message.source))
                assertEquals(message.source.count { it == '{' }, out.count { it == '{' }, out)
                assertEquals(message.source.count { it == '}' }, out.count { it == '}' }, out)
                assertTrue(message.source.isBlank() || (out.startsWith("[") && out.endsWith("]")), out)
            }
        }
    }
}
