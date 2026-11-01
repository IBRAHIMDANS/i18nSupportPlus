package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.ui.table.JBTable
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.table.DefaultTableModel

/** [TableKeyboard] on a table of its own: the keys reach the row actions and move across the locale cells. */
class TableKeyboardTest : PlatformBaseTest() {

    private var edits = 0
    private var opened = 0

    /** Two rows of Key, en, fr, Usage; only the locale columns are editable. */
    private fun table(): JTable {
        val model = DefaultTableModel(
            arrayOf(arrayOf<Any>("a", "A", "Af", 0), arrayOf<Any>("b", "B", "Bf", 0)),
            arrayOf<Any>("Key", "en", "fr", "Usage")
        )
        return JBTable(model).apply {
            cellSelectionEnabled = true
            TableKeyboard.install(this, { it in 1..2 }, { edits++ }, { opened++ })
        }
    }

    private fun press(table: JTable, stroke: KeyStroke, condition: Int = JComponent.WHEN_FOCUSED) {
        val name = table.getInputMap(condition).get(stroke)
        table.actionMap.get(name).actionPerformed(ActionEvent(table, ActionEvent.ACTION_PERFORMED, ""))
    }

    @Test
    fun `Enter edits and F4 opens the file`() {
        val table = table()

        press(table, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))
        press(table, KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0))

        Assertions.assertEquals(1, edits)
        Assertions.assertEquals(1, opened)
    }

    @Test
    fun `Tab moves to the next locale, skipping the usage and key columns`() {
        val table = table()
        table.changeSelection(0, 2, false, false)

        press(table, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)

        Assertions.assertEquals(1, table.selectedRow)
        Assertions.assertEquals(1, table.selectedColumn)
    }

    @Test
    fun `Shift+Tab moves back to the previous locale`() {
        val table = table()
        table.changeSelection(0, 2, false, false)

        val shiftTab = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK)
        press(table, shiftTab, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)

        Assertions.assertEquals(0, table.selectedRow)
        Assertions.assertEquals(1, table.selectedColumn)
    }

    @Test
    fun `typing or a double-click starts the editor of the selected cell`() {
        val table = table()

        Assertions.assertEquals(true, table.getClientProperty("JTable.autoStartsEdit"))
        val editor = table.getDefaultEditor(Any::class.java) as DefaultCellEditor
        Assertions.assertEquals(2, editor.clickCountToStart)
    }
}
