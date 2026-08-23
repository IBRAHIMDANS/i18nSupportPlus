package com.ibrahimdans.i18n.plugin.ide.settings

import java.awt.BorderLayout
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Shared plumbing for a typed detail form editing one item of a [ListEditorPanel].
 *
 * Every control it builds keeps the settings convention the UI test relies on: the
 * component's `name` is its bundle label. Every edit rewrites the selected item through
 * [mutate] — a copy, never an in-place change, so `Configurable.isModified` still sees it.
 *
 * [loading] guards the other direction: pushing a value into a control fires its listeners
 * too, and without the guard selecting an item would write it back over itself.
 */
internal abstract class ItemEditorPanel<T : Any> : JPanel(BorderLayout()) {

    /** True while the form is being filled from the selected item. */
    protected var loading = false

    /** Assigned by the subclass once its detail form exists. */
    protected lateinit var editor: ListEditorPanel<T>

    /** Called after the selected item was replaced, for whatever the form derives from it. */
    protected open fun onItemChanged() {}

    /** Replaces the selected item with [transform] applied to it. */
    protected fun mutate(transform: (T) -> T) {
        if (loading) return
        val current = editor.selected() ?: return
        val updated = transform(current)
        if (updated == current) return
        editor.replaceSelected(updated)
        onItemChanged()
    }

    protected fun boundTextField(label: String, columns: Int, apply: (T, String) -> T): JTextField {
        val field = JTextField(columns)
        field.name = label
        field.document.addDocumentListener(object : DocumentListener {
            override fun changedUpdate(event: DocumentEvent?) = mutate { apply(it, field.text) }
            override fun insertUpdate(event: DocumentEvent?) = mutate { apply(it, field.text) }
            override fun removeUpdate(event: DocumentEvent?) = mutate { apply(it, field.text) }
        })
        return field
    }

    protected fun boundCheckBox(label: String, apply: (T, Boolean) -> T): JCheckBox {
        val checkBox = JCheckBox(label)
        checkBox.name = label
        checkBox.addItemListener { mutate { item -> apply(item, checkBox.isSelected) } }
        return checkBox
    }

    protected fun boundSpinner(label: String, minimum: Int, maximum: Int, apply: (T, Int) -> T): JSpinner {
        val spinner = JSpinner(SpinnerNumberModel(minimum, minimum, maximum, 1))
        spinner.name = label
        spinner.addChangeListener { mutate { item -> apply(item, spinner.value as? Int ?: minimum) } }
        return spinner
    }

    /** Fills the form from [item], or empties and disables it when nothing is selected. */
    protected fun load(item: T?, fill: (T?) -> Unit) {
        loading = true
        try {
            fill(item)
        } finally {
            loading = false
        }
        onItemChanged()
    }
}
