package com.ibrahimdans.i18n.plugin.ide.screenshots

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.ui.remote.Component

@Remote("javax.swing.UIManager")
interface SwingDefaults {
    fun put(key: Any, value: Any): Any?
}

@Remote("com.intellij.openapi.editor.ex.EditorSettingsExternalizable")
interface EditorSettings {
    fun setBlinkCaret(blink: Boolean)
}

private val TEXT_COMPONENT_PREFIXES = listOf("TextField", "TextArea", "PasswordField", "FormattedTextField", "EditorPane", "TextPane")

/**
 * A blinking caret is visible in one capture and not in the next. Text components read their blink
 * rate when created, so this must run before the screenshots open any view or dialog.
 */
fun Driver.stopCaretBlinking() {
    val defaults = utility(SwingDefaults::class)
    for (prefix in TEXT_COMPONENT_PREFIXES) defaults.put("$prefix.caretBlinkRate", 0)
    service(EditorSettings::class).setBlinkCaret(false)
}

@Remote("javax.swing.JTable")
interface SwingTable {
    fun getColumnModel(): SwingColumnModel
}

@Remote("javax.swing.table.TableColumnModel")
interface SwingColumnModel {
    fun getColumn(index: Int): SwingColumn
}

@Remote("javax.swing.table.TableColumn")
interface SwingColumn {
    fun setPreferredWidth(width: Int)
    fun setWidth(width: Int)
}

/** Sets column widths as a user dragging the header borders would. */
fun Driver.resizeColumns(table: Component, widths: Map<Int, Int>) {
    withContext(OnDispatcher.EDT) {
        val columns = cast(table, SwingTable::class).getColumnModel()
        for ((index, width) in widths) {
            val column = columns.getColumn(index)
            column.setPreferredWidth(width)
            column.setWidth(width)
        }
    }
}
