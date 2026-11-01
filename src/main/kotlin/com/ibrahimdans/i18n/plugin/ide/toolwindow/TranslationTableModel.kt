package com.ibrahimdans.i18n.plugin.ide.toolwindow

import javax.swing.table.DefaultTableModel

/**
 * The Table View's model, whose locale cells are editable in place: not the [leadingColumns]
 * (Namespace and Key) nor the last one (Usage). The key column stays read-only — renaming is
 * RenameI18nKeyHandler's job.
 *
 * An edit is written through [save], straight to the matching translation file (one undo step
 * per cell), which creates the entry when the locale does not have it yet. Once written the cell
 * takes the new value and [onSaved] keeps the panel's rows in step; otherwise the cell keeps its
 * value and [onFailed] tells the user. Setting a cell to the value it holds writes nothing.
 */
internal class TranslationTableModel(
    private val leadingColumns: () -> Int,
    private val save: (key: String, locale: String, value: String) -> Boolean,
    private val onSaved: (key: String, locale: String, value: String) -> Unit,
    private val onFailed: (key: String, locale: String) -> Unit,
) : DefaultTableModel() {

    // Editable: locale columns only — not "Namespace"/"Key" (leading) nor "Usage" (last).
    override fun isCellEditable(row: Int, column: Int): Boolean =
        column in leadingColumns() until columnCount - 1

    override fun setValueAt(aValue: Any?, row: Int, column: Int) {
        if (!isCellEditable(row, column)) {
            super.setValueAt(aValue, row, column)
            return
        }
        val newValue = aValue?.toString() ?: ""
        val oldValue = getValueAt(row, column)?.toString() ?: ""
        if (newValue == oldValue) return
        val key = getValueAt(row, leadingColumns() - 1) as? String ?: return
        val locale = getColumnName(column)

        if (save(key, locale, newValue)) {
            super.setValueAt(newValue, row, column)
            onSaved(key, locale, newValue)
        } else {
            onFailed(key, locale)
        }
    }
}
