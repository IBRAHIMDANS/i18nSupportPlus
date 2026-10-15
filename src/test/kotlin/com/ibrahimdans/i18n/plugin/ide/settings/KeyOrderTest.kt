package com.ibrahimdans.i18n.plugin.ide.settings

import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
}
