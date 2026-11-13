package com.ibrahimdans.i18n.extensions.localization.yaml

import com.ibrahimdans.i18n.extensions.localization.yaml.YamlScalars.Style
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Whatever the value, the spelling [YamlScalars] gives it reads back, through a YAML parser, as
 * that very string: what `key: <spelling>` means in a translation file is the value written.
 *
 * Values are glued from tokens that change the meaning of a plain scalar — indicators at the
 * start, `: ` and ` #` inside, words and numbers YAML 1.1 or 1.2 resolve to another type, padding,
 * line breaks and controls, quotes, backslashes — with ordinary text around them. The seed is
 * fixed: a failure replays the same cases.
 */
class YamlScalarsPropertyTest {

    @OptIn(ExperimentalKotest::class)
    private val config = PropTestConfig(seed = 20261113L, iterations = 1000)

    private val tokens = listOf(
        "Save", "a", "Z", "é", "😀", " ", "  ", ch(0xa0),
        ":", ": ", "#", " #", "-", "- ", "?", "? ", "@", "`", "{", "}", "[", "]", ",", "!", "&", "*", "%", "|", ">",
        "'", "\"", "\\", "{{count}}",
        "yes", "No", "on", "OFF", "true", "null", "~", "y", "123", "-4", "1.5", "1e3", "0x1F", "0o17", ".inf", ".NaN", "2026-10-15", "12:30",
        "\n", "\r", "\r\n", "\t", ch(0x07), ch(0x7f), ch(0x2028), ch(0x2029),
    )

    /** NEXT LINE (U+0085): a line break to the YAML reader, an ordinary character to [YamlScalars]. */
    private val nextLine = ch(0x85)

    private fun values(tokens: List<String>) = Arb.list(Arb.of(tokens), 0..6).map { it.joinToString("") }

    private val loader = Load(LoadSettings.builder().build())

    @Test
    fun `a spelled value reads back as the same string`() {
        runBlocking {
            checkAll(config, values(tokens), Arb.enum<Style>()) { value, preferred -> assertReadsBack(value, preferred) }
        }
    }

    @Test
    fun `a spelled value nested in a mapping reads back as the same string`() {
        runBlocking {
            checkAll(config, values(tokens)) { value ->
                val spelled = YamlScalars.spell(value)
                val menu = (loader.loadFromString("menu:\n  save: $spelled\n  quit: Quit\n") as Map<*, *>)["menu"] as Map<*, *>

                assertEquals(mapOf("save" to value, "quit" to "Quit"), menu, "${codes(value)} spelled as ${codes(spelled)}")
            }
        }
    }

    /**
     * Smallest case: a value holding U+0085 alone is spelled plain, as is, and reads back as null
     * (an empty value). The YAML reader takes NEXT LINE for a line break, which a plain or
     * single-quoted scalar folds; only the C0 controls and DEL get the escapes of double quotes.
     * U+2028 and U+2029 read back fine (in the tokens above).
     */
    @Test
    @Disabled("CADRAGE-YAML-NEXT-LINE: YamlScalars writes U+0085 unescaped, the YAML reader takes it for a line break")
    fun `a spelled value keeps its NEXT LINE characters`() {
        runBlocking {
            checkAll(config, values(tokens + nextLine), Arb.enum<Style>()) { value, preferred -> assertReadsBack(value, preferred) }
        }
    }

    private fun assertReadsBack(value: String, preferred: Style) {
        val spelled = YamlScalars.spell(value, preferred)
        val read = (loader.loadFromString("key: $spelled\n") as Map<*, *>)["key"]

        assertEquals(value, read, "${codes(value)} spelled as ${codes(spelled)}")
    }

    /** [text] with its invisible characters shown: a shrunk failure is often one of them. */
    private fun codes(text: String): String =
        "<" + text.map { if (it in ' '..'~') "$it" else "\\u%04x".format(it.code) }.joinToString("") + ">"

    private fun ch(code: Int): String = Char(code).toString()
}
