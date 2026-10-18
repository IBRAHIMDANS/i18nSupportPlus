package com.ibrahimdans.i18n.plugin.ide.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import kotlin.random.Random

class KeyOrderTest {

    private val keys = listOf("cherry", "_meta", "Banana", "apple", "2fa", "Zebra", "10items")

    @Test
    fun `ignore case mixes upper and lower case keys`() {
        assertEquals(
            listOf("10items", "2fa", "_meta", "apple", "Banana", "cherry", "Zebra"),
            keys.sortedWith(KeyOrder.IGNORE_CASE.comparator)
        )
    }

    @Test
    fun `character code puts every upper case key before the lower case ones, like the IDE`() {
        assertEquals(
            listOf("10items", "2fa", "Banana", "Zebra", "_meta", "apple", "cherry"),
            keys.sortedWith(KeyOrder.CHARACTER_CODE.comparator)
        )
    }

    @Test
    fun `ignore case sees keys differing only by case as equal`() {
        assertEquals(0, KeyOrder.IGNORE_CASE.comparator.compare("Apple", "apple"))
    }

    @Test
    fun `the default keeps the order the plugin sorted in before the setting existed`() {
        assertEquals(KeyOrder.IGNORE_CASE, Config().keyOrder)
    }

    @Test
    fun `the chosen order survives a save and a reload`() {
        val saved = XmlSerializer.serialize(Settings().apply { keyOrder = KeyOrder.CHARACTER_CODE })

        val settings = Settings()
        settings.loadState(XmlSerializer.deserialize(saved, Settings::class.java))

        assertEquals(KeyOrder.CHARACTER_CODE, settings.keyOrder)
    }

    @Test
    fun `the natural order survives a save and a reload, stored by its name`() {
        val saved = XmlSerializer.serialize(Settings().apply { keyOrder = KeyOrder.NATURAL })

        val settings = Settings()
        settings.loadState(XmlSerializer.deserialize(saved, Settings::class.java))

        assertEquals(KeyOrder.NATURAL, settings.keyOrder)
        assertTrue(JDOMUtil.write(saved).contains("\"NATURAL\""))
    }

    private val natural = KeyOrder.NATURAL.comparator

    private fun assertBefore(first: String, second: String) {
        assertTrue(natural.compare(first, second) < 0, "$first should sort before $second")
        assertTrue(natural.compare(second, first) > 0, "$second should sort after $first")
    }

    @Test
    fun `natural order compares numbers by value`() {
        assertBefore("2", "10")
        assertBefore("item2", "item10")
        assertBefore("a2b", "a10a")
        assertBefore("item9", "item10")
    }

    @Test
    fun `natural order ignores case first`() {
        assertBefore("apple", "Banana")
        assertBefore("Item2", "item10")
        assertBefore("Apple", "apple")
    }

    @Test
    fun `natural order compares very long numbers without parsing them`() {
        val big = "9".repeat(40)
        val bigger = "1" + "0".repeat(40)
        assertBefore("n$big", "n$bigger")
        assertBefore("n${big}8", "n${big}9")
        assertEquals(0, natural.compare("n$big", "n$big"))
    }

    @Test
    fun `natural order puts fewer leading zeros first among equal numbers`() {
        assertBefore("a1", "a01")
        assertBefore("a01", "a001")
        assertBefore("a01", "a2")
        assertBefore("a0", "a00")
        assertBefore("a1b01", "a01b1")
    }

    @Test
    fun `natural order sorts a mixed list`() {
        assertEquals(
            listOf("1", "2fa", "10items", "_meta", "apple", "Banana", "item", "item1", "item01", "Item2", "item10", "item10a", "Zebra"),
            listOf("item10", "Zebra", "item01", "_meta", "Item2", "10items", "apple", "item", "2fa", "item10a", "1", "Banana", "item1")
                .sortedWith(natural)
        )
    }

    @Test
    fun `natural order is a total order on every short key over a small alphabet`() {
        val alphabet = listOf('a', 'A', 'b', '0', '1', '2', '_')
        var keys = listOf("")
        val all = mutableListOf("")
        repeat(3) {
            keys = keys.flatMap { key -> alphabet.map { key + it } }
            all += keys
        }

        val sorted = all.shuffled(Random(42)).sortedWith(natural)

        // Every pair taken in sorted order compares strictly below: the order is antisymmetric,
        // never ties distinct keys, and is transitive since it matches the positions in the list.
        for (x in sorted.indices) {
            assertEquals(0, natural.compare(sorted[x], sorted[x]))
            for (y in x + 1 until sorted.size) {
                val forward = natural.compare(sorted[x], sorted[y])
                val backward = natural.compare(sorted[y], sorted[x])
                if (forward >= 0 || backward <= 0) fail<Unit>("${sorted[x]} vs ${sorted[y]}: $forward / $backward")
            }
        }
    }
}
