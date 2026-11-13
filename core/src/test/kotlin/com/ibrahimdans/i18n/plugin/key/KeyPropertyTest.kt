package com.ibrahimdans.i18n.plugin.key

import com.ibrahimdans.i18n.plugin.ide.settings.SeparatorRules
import com.ibrahimdans.i18n.plugin.key.parser.KeyParserBuilder
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.tree.KeyComposer
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.tree.Separators
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Keys composed from a translation file's tree, then parsed back as the code writes them: a key
 * the plugin offers (completion, *Copy key*, references) must name the same namespace and segments
 * once read from a call.
 *
 * Segments never hold a separator nor end like a plural form (`_one`, `-1`): the composer strips
 * those on purpose ([PluralKey.stripSuffix]). Separators are the pairs [SeparatorRules] accepts.
 * The seed is fixed: a failure replays the same cases.
 */
class KeyPropertyTest {

    @OptIn(ExperimentalKotest::class)
    private val config = PropTestConfig(seed = 20261113L, iterations = 500)

    // One character each, as SeparatorRules requires: a longer separator is refused by the settings
    // (Fault.SEVERAL_CHARACTERS) because the tokenizer splits it character by character. Pairs the
    // rules refuse (refused characters such as `$`, the same value twice) are filtered out below.
    private val separatorChars = listOf(":", ".", "/", "|", "#", "$", "~")

    private val segmentChars = listOf("a", "b", "Z", "0", "9", "_", "-", "é", "日", "😀", "@", "(", " ")
        .flatMap { s -> s.codePoints().toArray().map(::Codepoint) }

    private val segment = Arb.string(1..8, Arb.of(segmentChars))

    private val separators = Arb.bind(Arb.of(separatorChars), Arb.of(separatorChars)) { ns, key -> ns to key }
        .filter { (ns, key) -> SeparatorRules.check(ns, key, "-").isEmpty() }

    private val composer = object : KeyComposer<String> {}

    private fun parse(key: String, ns: String, keySep: String, emptyNamespace: Boolean = false, firstComponentNamespace: Boolean = false) =
        KeyParserBuilder.withSeparators(ns, keySep).build()
            .parse(RawKey(listOf(KeyElement.literal(key))), emptyNamespace, firstComponentNamespace)

    /** Segments the composer writes as they are: no plural suffix at the end of the joined path. */
    private fun keepsPlural(segments: List<String>, keySep: String) =
        segments.joinToString(keySep).let { PluralKey.stripSuffix(it, "-") == it }

    @Test
    fun `a composed key with its namespace parses back to the same namespace and segments`() {
        runBlocking {
            checkAll(config, separators, segment, Arb.list(segment, 1..5)) { (nsSep, keySep), ns, segments ->
                if (!keepsPlural(segments, keySep)) return@checkAll
                val key = composer.composeKey(listOf(ns) + segments, Separators(nsSep, keySep, "-"), defaultNs = emptyList())

                val parsed = parse(key, nsSep, keySep)!!

                assertEquals(ns, parsed.ns?.text, key)
                assertEquals(segments, parsed.compositeKey.map { it.text }, key)
                assertEquals(key, parsed.source)
            }
        }
    }

    @Test
    fun `a key in the default namespace is written without it and parses back without one`() {
        runBlocking {
            checkAll(config, separators, segment, Arb.list(segment, 1..5)) { (nsSep, keySep), ns, segments ->
                if (!keepsPlural(segments, keySep)) return@checkAll
                val key = composer.composeKey(listOf(ns) + segments, Separators(nsSep, keySep, "-"), defaultNs = listOf(ns))

                val parsed = parse(key, nsSep, keySep)!!

                assertNull(parsed.ns, key)
                assertEquals(segments, parsed.compositeKey.map { it.text }, key)
            }
        }
    }

    @Test
    fun `a key whose first component is the namespace parses back with it`() {
        runBlocking {
            checkAll(config, separators, segment, Arb.list(segment, 1..5)) { (nsSep, keySep), ns, segments ->
                if (!keepsPlural(segments, keySep)) return@checkAll
                val key = composer.composeKey(listOf(ns) + segments, Separators(nsSep, keySep, "-"), defaultNs = emptyList(), firstComponentNs = true)

                val parsed = parse(key, nsSep, keySep, emptyNamespace = true, firstComponentNamespace = true)!!

                assertEquals(ns, parsed.ns?.text, key)
                assertEquals(segments, parsed.compositeKey.map { it.text }, key)
            }
        }
    }

    @Test
    fun `a key split into several literals parses like the whole literal`() {
        runBlocking {
            checkAll(config, separators, segment, Arb.list(segment, 1..5), Arb.list(Arb.int(0..40), 0..4)) { (nsSep, keySep), ns, segments, cuts ->
                val key = ns + nsSep + segments.joinToString(keySep)
                val bounds = (cuts.map { it % (key.length + 1) } + 0 + key.length).distinct().sorted()
                val pieces = bounds.zipWithNext { from, to -> KeyElement.literal(key.substring(from, to)) }

                val whole = parse(key, nsSep, keySep)
                val split = KeyParserBuilder.withSeparators(nsSep, keySep).build().parse(RawKey(pieces))

                assertEquals(whole?.ns, split?.ns, key)
                assertEquals(whole?.compositeKey, split?.compositeKey, key)
                assertEquals(key, split?.source)
            }
        }
    }

    @Test
    fun `a key with an empty segment or a dangling separator is not a key`() {
        runBlocking {
            checkAll(config, separators, Arb.list(segment, 2..5), Arb.int(0..2)) { (nsSep, keySep), segments, shape ->
                val key = when (shape) {
                    0 -> segments.joinToString(keySep) + keySep
                    1 -> keySep + segments.joinToString(keySep)
                    else -> segments.first() + keySep + keySep + segments.drop(1).joinToString(keySep)
                }

                assertNull(parse(key, nsSep, keySep), key)
            }
        }
    }
}
