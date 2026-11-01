package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.intellij.ui.components.JBTextField
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.DefaultCellEditor
import javax.swing.JTable
import javax.swing.KeyStroke

/** Input map keys for the shortcuts the table and its cell editor bind on themselves. */
private const val ACTION_EDIT = "i18n.table.edit"
private const val ACTION_OPEN_FILE = "i18n.table.openFile"
private const val ACTION_NEXT_LOCALE = "i18n.table.nextLocale"
private const val ACTION_PREVIOUS_LOCALE = "i18n.table.previousLocale"
private const val ACTION_COMMIT_DOWN = "i18n.table.commitDown"
private const val ACTION_CANCEL = "i18n.table.cancelEdit"

/**
 * The cell [columnStep] editable columns or [rowStep] rows away from ([row], [column]), or null
 * when there is none. A column step skips the columns [editable] refuses — the key in front, the
 * usage behind — and wraps to the first editable column of the next row (the last of the
 * previous one going backwards), as Tab does in a spreadsheet. A row step keeps the column.
 */
internal fun nextEditableCell(
    row: Int,
    column: Int,
    rowStep: Int,
    columnStep: Int,
    rowCount: Int,
    columnCount: Int,
    editable: (column: Int) -> Boolean,
): Pair<Int, Int>? {
    if (rowStep != 0) return (row + rowStep).takeIf { it in 0 until rowCount }?.let { it to column }
    if (columnStep == 0) return null
    var current = row
    var candidate = column + columnStep
    while (current in 0 until rowCount) {
        while (candidate in 0 until columnCount) {
            if (editable(candidate)) return current to candidate
            candidate += columnStep
        }
        current += columnStep
        candidate = if (columnStep > 0) 0 else columnCount - 1
    }
    return null
}

/**
 * The keyboard of the Table View: Enter edits, F4 opens the file — the two gestures the panel
 * only offered through the mouse — and a locale cell is edited as in a spreadsheet.
 *
 * Installed once by [TableViewPanel] on its own table. [editableColumn] says whether a *model*
 * column takes the in-place editor: the locale columns do, the key and usage columns do not.
 */
internal object TableKeyboard {

    fun install(table: JTable, editableColumn: (modelColumn: Int) -> Boolean, onEdit: () -> Unit, onOpenFile: () -> Unit) {
        registerShortcuts(table, onEdit, onOpenFile)
        registerSpreadsheetKeys(table, editableColumn)
    }

    /**
     * Bound on the table's own `WHEN_FOCUSED` map, so an active cell editor (which holds the
     * focus itself) still commits on Enter rather than reopening a dialog.
     */
    private fun registerShortcuts(table: JTable, onEdit: () -> Unit, onOpenFile: () -> Unit) {
        table.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTION_EDIT)
        table.actionMap.put(ACTION_EDIT, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onEdit()
        })
        table.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), ACTION_OPEN_FILE)
        table.actionMap.put(ACTION_OPEN_FILE, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onOpenFile()
        })
    }

    /**
     * The keys of a spreadsheet: translating a whole locale used to take a double-click per cell.
     *
     * Typing on a selected locale cell starts its editor, which takes the focus. Inside the
     * editor, Enter commits and moves down, Tab and Shift+Tab commit and move across the locale
     * columns, Escape cancels. The editor's text field would otherwise keep Tab for focus
     * traversal and consume Enter itself, so both are bound on it; on the table, Tab and
     * Shift+Tab move across the locales without editing.
     */
    private fun registerSpreadsheetKeys(table: JTable, editableColumn: (Int) -> Boolean) {
        table.putClientProperty("JTable.autoStartsEdit", true)
        table.surrendersFocusOnKeystroke = true

        val field = JBTextField().apply { focusTraversalKeysEnabled = false }
        table.setDefaultEditor(Any::class.java, DefaultCellEditor(field).apply { clickCountToStart = 2 })

        val moves = mapOf(
            ACTION_NEXT_LOCALE to { moveSelection(table, editableColumn, rowStep = 0, columnStep = 1) },
            ACTION_PREVIOUS_LOCALE to { moveSelection(table, editableColumn, rowStep = 0, columnStep = -1) },
            ACTION_COMMIT_DOWN to { moveSelection(table, editableColumn, rowStep = 1, columnStep = 0) },
            ACTION_CANCEL to { table.cellEditor?.cancelCellEditing(); Unit },
        )
        val tab = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0)
        val shiftTab = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK)
        field.inputMap.put(tab, ACTION_NEXT_LOCALE)
        field.inputMap.put(shiftTab, ACTION_PREVIOUS_LOCALE)
        field.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTION_COMMIT_DOWN)
        field.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ACTION_CANCEL)
        table.getInputMap(JTable.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(tab, ACTION_NEXT_LOCALE)
        table.getInputMap(JTable.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(shiftTab, ACTION_PREVIOUS_LOCALE)
        for ((name, run) in moves) {
            val action = object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    run()
                }
            }
            field.actionMap.put(name, action)
            table.actionMap.put(name, action)
        }
    }

    /**
     * Commits the edit in progress, if any, then selects the cell [nextEditableCell] gives. An
     * edit the editor refuses to stop keeps the caret where it is.
     */
    private fun moveSelection(table: JTable, editableColumn: (Int) -> Boolean, rowStep: Int, columnStep: Int) {
        if (table.isEditing && table.cellEditor?.stopCellEditing() == false) return
        val row = table.selectedRow
        val column = table.selectedColumn
        if (row < 0 || column < 0) return
        val target = nextEditableCell(row, column, rowStep, columnStep, table.rowCount, table.columnCount) {
            editableColumn(table.convertColumnIndexToModel(it))
        } ?: return
        table.changeSelection(target.first, target.second, false, false)
        table.requestFocusInWindow()
    }
}
