package com.ibrahimdans.i18n.plugin.edit

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.ibrahimdans.i18n.extensions.localization.yaml.YamlScalars
import com.ibrahimdans.i18n.plugin.ide.settings.KeyOrder
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.junit.jupiter.api.Test

/**
 * Adding a key to a generated translation file, in JSON and in YAML: the file still parses, holds
 * the new key with its exact value, and every other key as it was. A key the file already holds,
 * or one running into a value, gets no edit at all.
 *
 * Files are trees of a few levels over a small key alphabet, so that new keys often land in an
 * existing object, sometimes collide. JSON files are laid out pretty or on one line; YAML files
 * as block mappings indented by 2 or 4, their values spelled by [YamlScalars]. The seed is fixed.
 */
class TranslationEditorPropertyTest {

    @OptIn(ExperimentalKotest::class)
    private val config = PropTestConfig(seed = 20261113L, iterations = 500)

    private val names = listOf("a", "b", "c", "menu", "save")

    private val tricky = listOf("a", "Z", " ", "é", "😀", "\"", "\\", "/", "'", ":", ": ", "#", " #", "-", "{", "}", "[", "]", ",", "<", "&",
        "yes", "null", "1.5", "\n", "\r", "\t", "\u0007", Char(0x2028).toString(), Char(0x2029).toString())

    /**
     * NEXT LINE (U+0085): JSON carries it as is; the YAML reader takes it for a line break, which
     * [YamlScalars] does not escape yet (CADRAGE-YAML-NEXT-LINE, see `YamlScalarsPropertyTest`),
     * so the YAML values leave it out.
     */
    private val nextLine = Char(0x85).toString()

    private fun values(tokens: List<String>) = Arb.list(Arb.of(tokens), 0..5).map { it.joinToString("") }

    private val values = values(tricky)

    private val jsonValues = values(tricky + nextLine)

    /** A JSON key may be any string: the editor quotes it. */
    private val jsonNames = Arb.string(1..4, Arb.of((tricky + names).flatMap { s -> s.codePoints().toArray().map(::Codepoint) }))

    private val orders = Arb.of(KeyOrder.IGNORE_CASE.comparator).orNull(0.5)

    /** A tree of translations: each entry a string, or a non-empty object one level down. */
    private fun tree(keys: Arb<String>, depth: Int, minSize: Int): Arb<Map<String, Any>> = arbitrary {
        val entries = mutableMapOf<String, Any>()
        repeat(Arb.int(minSize..4).bind()) {
            val key = keys.bind()
            val nested = depth > 0 && Arb.boolean().bind()
            entries[key] = if (nested) tree(keys, depth - 1, 1).bind() else values.bind()
        }
        entries
    }

    private fun path(keys: Arb<String>) = Arb.list(keys, 1..3)

    /** [tree] with [value] at [path], or null when [path] is there already or goes through a value. */
    private fun withKey(tree: Map<String, Any>, path: List<String>, value: String): Map<String, Any>? {
        val head = path.first()
        val child = tree[head]
        if (path.size == 1) return if (child == null) tree + (head to value) else null
        return when (child) {
            null -> tree + (head to path.drop(1).foldRight(value as Any) { name, inner -> mapOf(name to inner) })
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") withKey(child as Map<String, Any>, path.drop(1), value)?.let { tree + (head to it) }
            else -> null
        }
    }

    // --- JSON ---

    private val pretty = GsonBuilder().setPrettyPrinting().create()
    private val compact = GsonBuilder().create()

    private fun valueOf(element: JsonElement): Any =
        if (element.isJsonObject) element.asJsonObject.entrySet().associate { (k, v) -> k to valueOf(v) } else element.asString

    @Test
    fun `a key added to a JSON file reads back with every other key`() {
        runBlocking {
            checkAll(config, tree(jsonNames, 2, 0), Arb.boolean(), path(jsonNames), jsonValues, orders) { tree, onePerLine, path, value, order ->
                val source = (if (onePerLine) pretty else compact).toJson(tree)
                val edits = JsonTranslationEditor.insert(source, path, value, order)
                val expected = withKey(tree, path, value)

                if (expected == null) {
                    assertTrue(edits.isEmpty(), "no edit for $path in <$source>: $edits")
                } else {
                    val result = TextEdit.apply(source, edits)
                    assertEquals(expected, valueOf(JsonParser.parseString(result)), "after adding $path to <$source>:\n$result")
                }
            }
        }
    }

    // --- YAML ---

    private val loader = Load(LoadSettings.builder().build())

    /** [tree] as a block mapping, each level [indent] deeper, its values spelled by [YamlScalars]. */
    private fun yaml(tree: Map<String, Any>, indent: String, level: Int = 0): String = buildString {
        tree.forEach { (key, value) ->
            append(indent.repeat(level)).append(key).append(':')
            if (value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                append('\n').append(yaml(value as Map<String, Any>, indent, level + 1))
            } else {
                append(' ').append(YamlScalars.spell(value as String)).append('\n')
            }
        }
    }

    @Test
    fun `a key added to a YAML file reads back with every other key`() {
        runBlocking {
            checkAll(config, tree(Arb.of(names), 2, 0), Arb.of("  ", "    "), path(Arb.of(names)), values, orders) { tree, indent, path, value, order ->
                val source = yaml(tree, indent)
                val edits = YamlTranslationEditor(indent).insert(source, path, value, order)
                val expected = withKey(tree, path, value)

                if (expected == null) {
                    assertTrue(edits.isEmpty(), "no edit for $path in <$source>: $edits")
                } else {
                    val result = TextEdit.apply(source, edits)
                    assertEquals(expected, loader.loadFromString(result), "after adding $path to <$source>:\n$result")
                }
            }
        }
    }
}
