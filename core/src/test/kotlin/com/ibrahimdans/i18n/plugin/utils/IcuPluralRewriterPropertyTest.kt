package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.tree.PluralCategories
import com.ibrahimdans.i18n.plugin.utils.IcuPluralRewriter.Result
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * ICU messages rewritten for another language's plural categories, checked against a model.
 *
 * Each case is a message tree drawn at random, then written as ICU text: text with quoted braces and
 * apostrophes, variables, formatted values, and `plural` / `select` / `selectordinal` blocks, one
 * inside another at most, as [IcuPluralRewriter] supports. The expected rewrite is built from the
 * tree with what the rewriter promises: a plural block keeps its exact branches (`=0`) and its
 * `offset:`, takes the categories the locale needs in CLDR order, a missing one copied from `other`,
 * and drops the others; `select` and `selectordinal` keep their branches; branch texts and the text
 * outside blocks are kept as written; the whitespace between branches is rebuilt as one space.
 *
 * The tree is drawn from a seed the property hands out, itself fixed: a failure replays the same cases.
 */
class IcuPluralRewriterPropertyTest {

    @OptIn(ExperimentalKotest::class)
    private val config = PropTestConfig(seed = 20261114L, iterations = 500)

    private val locales = Arb.of("en", "fr", "ru", "ar", "ja", "pl", "cs", "sl", "he")

    private sealed interface Segment

    private data class Text(val text: String) : Segment

    /** `{name}` or a formatted value (`{n, number}`): no branches, kept as written. */
    private data class Variable(val text: String) : Segment

    private data class Branch(val selector: String, val body: List<Segment>)

    /**
     * `{argument, type, …}`: [header] as written up to the second comma, the [offset] of a plural,
     * [gaps] the whitespace written before each part.
     */
    private data class Block(
        val header: String, val type: String, val offset: Int?, val branches: List<Branch>, val gaps: List<String>,
    ) : Segment

    // Text the rewriter copies as it is: plain characters, `#`, and the apostrophe forms ICU reads as
    // text (`''`, a quoted brace or hash, an apostrophe between letters as in `l'arbre`).
    private val textPieces = listOf(
        "a", "Z", "0", " ", ",", ".", ":", "!", "#", "é", "日", "😀", "-", "=",
        "''", "'{'", "'}'", "'{}'", "'#'", "l'a",
    )
    private val variables = listOf("{name}", "{n}", "{n, number}", "{total, number, integer}", "{d, date, short}")
    private val gapPieces = listOf(" ", "  ", "\n", "\t", "\n  ")
    private val ordinalCategories = listOf("one", "two", "few", "other")
    private val genders = listOf("male", "female", "other")

    private fun Random.text(): Text = Text(List(nextInt(0, 6)) { textPieces.random(this) }.joinToString(""))

    /** A branch body: text and variables, and blocks while [depth] allows one more level. */
    private fun Random.body(depth: Int): List<Segment> = List(nextInt(1, 5)) {
        when {
            depth < 2 && nextInt(4) == 0 -> block(depth)
            nextInt(3) == 0 -> Variable(variables.random(this))
            else -> text()
        }
    }

    /** A subset of [all] holding `other`, in a random order. */
    private fun Random.selectors(all: List<String>): List<String> =
        (all.filter { it == PluralCategories.OTHER || nextBoolean() }).shuffled(this)

    private fun Random.block(depth: Int): Block {
        val argument = listOf("count", "n", "gender", "place").random(this)
        val type = listOf("plural", "plural", "select", "selectordinal").random(this)
        val header = listOf("{$argument, $type,", "{ $argument ,$type,", "{$argument,  $type ,").random(this)
        val selectors = when (type) {
            "plural" -> (selectors(PluralCategories.ALL) + listOf("=0", "=1", "=2").filter { nextInt(3) == 0 }).shuffled(this)
            "select" -> selectors(genders)
            else -> selectors(ordinalCategories)
        }
        val offset = if (type == "plural" && nextInt(4) == 0) nextInt(1, 3) else null
        val branches = selectors.map { Branch(it, body(depth + 1)) }
        val parts = branches.size + (if (offset != null) 1 else 0)
        return Block(header, type, offset, branches, List(parts) { gapPieces.random(this) })
    }

    private fun Random.message(): List<Segment> = List(nextInt(1, 5)) {
        when (nextInt(3)) {
            0 -> block(0)
            1 -> Variable(variables.random(this))
            else -> text()
        }
    }

    /** The tree written as a translator would: the whitespace between parts as drawn. */
    private fun written(segments: List<Segment>): String = segments.joinToString("") { segment ->
        when (segment) {
            is Text -> segment.text
            is Variable -> segment.text
            is Block -> {
                val parts = listOfNotNull(segment.offset?.let { "offset:$it" }) +
                    segment.branches.map { "${it.selector} {${written(it.body)}}" }
                segment.header + parts.indices.joinToString("") { segment.gaps[it] + parts[it] } + "}"
            }
        }
    }

    /** What the rewriter promises for [segments] in a language needing [needed]; [copied] collects the copies. */
    private fun expected(segments: List<Segment>, needed: Set<String>, copied: MutableSet<String>): String =
        segments.joinToString("") { segment ->
            when (segment) {
                is Text -> segment.text
                is Variable -> segment.text
                is Block -> {
                    val branches = if (segment.type == "plural") {
                        val exact = segment.branches.filterNot { it.selector in PluralCategories.ALL }
                        val byCategory = segment.branches.associateBy { it.selector }
                        val other = byCategory.getValue(PluralCategories.OTHER)
                        exact + PluralCategories.ALL.filter { it in needed }.map { category ->
                            byCategory[category] ?: Branch(category, other.body).also { copied += category }
                        }
                    } else {
                        segment.branches
                    }
                    val parts = listOfNotNull(segment.offset?.let { "offset:$it" }) +
                        branches.map { "${it.selector} {${expected(it.body, needed, copied)}}" }
                    segment.header + " " + parts.joinToString(" ") + "}"
                }
            }
        }

    @Test
    fun `a message is rewritten with the categories its language needs, everything else kept`() {
        runBlocking {
            checkAll(config, Arb.long(), locales, Arb.boolean()) { seed, locale, largeNumbers ->
                val tree = Random(seed).message()
                val copied = mutableSetOf<String>()
                val want = expected(tree, PluralCategories.of(locale, largeNumbers), copied)

                assertEquals(
                    Result.Rewritten(want, copied),
                    IcuPluralRewriter.forLocale(written(tree), locale, largeNumbers),
                    "${written(tree)} for $locale"
                )
            }
        }
    }

    @Test
    fun `a rewritten message has no mismatch left, and rewriting it again changes nothing`() {
        runBlocking {
            checkAll(config, Arb.long(), locales, Arb.boolean()) { seed, locale, largeNumbers ->
                val message = written(Random(seed).message())
                val once = IcuPluralRewriter.forLocale(message, locale, largeNumbers) as Result.Rewritten

                assertEquals(emptyList<String>(), IcuPluralRewriter.mismatches(once.text, locale, largeNumbers), once.text)
                assertEquals(
                    Result.Rewritten(once.text, emptySet()),
                    IcuPluralRewriter.forLocale(once.text, locale, largeNumbers),
                    once.text
                )
            }
        }
    }

    @Test
    fun `a block two levels down is left to the user`() {
        runBlocking {
            checkAll(config, Arb.long(), locales) { seed, locale ->
                val random = Random(seed)
                val inner = written(listOf(random.block(1)))
                val message = "{a, select, other {${random.text().text}{b, select, x {$inner} other {z}}}}"

                assertEquals(Result.Unsupported, IcuPluralRewriter.forLocale(message, locale), message)
            }
        }
    }
}
