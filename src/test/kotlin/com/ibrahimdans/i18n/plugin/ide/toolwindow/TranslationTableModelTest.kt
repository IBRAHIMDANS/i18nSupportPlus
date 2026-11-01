package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** [TranslationTableModel]: which cells are editable, and what an edit writes, keeps or reports. */
class TranslationTableModelTest : PlatformBaseTest() {

    private val saved = mutableListOf<Triple<String, String, String>>()
    private val failed = mutableListOf<Pair<String, String>>()
    private var writes = 0

    /** One row of Key, en, Usage, after the panel's single leading column. */
    private fun model(accepts: Boolean = true) = TranslationTableModel(
        leadingColumns = { 1 },
        save = { _, _, _ -> writes++; accepts },
        onSaved = { key, locale, value -> saved += Triple(key, locale, value) },
        onFailed = { key, locale -> failed += key to locale },
    ).apply {
        setDataVector(arrayOf(arrayOf<Any>("common:save", "Save", 0)), arrayOf<Any>("Key", "en", "Usage"))
    }

    @Test
    fun `only the locale columns are editable`() {
        val model = model()

        Assertions.assertFalse(model.isCellEditable(0, 0), "the key")
        Assertions.assertTrue(model.isCellEditable(0, 1), "a locale")
        Assertions.assertFalse(model.isCellEditable(0, 2), "the usage")
    }

    @Test
    fun `an edit is written, shown and reported`() {
        val model = model()

        model.setValueAt("Save it", 0, 1)

        Assertions.assertEquals("Save it", model.getValueAt(0, 1))
        Assertions.assertEquals(listOf(Triple("common:save", "en", "Save it")), saved)
        Assertions.assertTrue(failed.isEmpty())
    }

    @Test
    fun `an edit that cannot be written keeps the cell and reports the failure`() {
        val model = model(accepts = false)

        model.setValueAt("Save it", 0, 1)

        Assertions.assertEquals("Save", model.getValueAt(0, 1))
        Assertions.assertEquals(listOf("common:save" to "en"), failed)
        Assertions.assertTrue(saved.isEmpty())
    }

    @Test
    fun `the value the cell holds already writes nothing`() {
        val model = model()

        model.setValueAt("Save", 0, 1)

        Assertions.assertEquals(0, writes)
    }
}
