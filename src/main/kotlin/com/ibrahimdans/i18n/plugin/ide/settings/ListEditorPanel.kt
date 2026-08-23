package com.ibrahimdans.i18n.plugin.ide.settings

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel

/**
 * Master-detail editor: the items on the left, a caller-supplied form on the right.
 *
 * Plain [JList] and [JButton] on purpose, like the rest of the settings panel: JBList and
 * ToolbarDecorator need a running Application, and this panel must stay buildable in the
 * plain Swing settings test.
 *
 * That trade was re-examined when the buttons were reported as barely visible. `ToolbarDecorator`
 * is the platform idiom and would render them as the `+` / `−` pair users expect everywhere else
 * in the IDE — but it would take these panels out of reach of the only tests that cover them at
 * all, and those tests are what caught the clipping below. The trade stands: plain buttons, laid
 * out with room to breathe, and a headless suite that can still measure them.
 *
 * Items are never edited in place — [replaceSelected] swaps the element for a new one. The
 * Configurable snapshots the settings by copying the *list* and not its elements, so an
 * in-place edit would be invisible to `isModified` and the Apply button would stay grey.
 */
internal class ListEditorPanel<T : Any>(
    private val items: MutableList<T>,
    detailForm: JComponent,
    private val newItem: () -> T,
    private val labelOf: (T) -> String,
    listName: String,
    addLabel: String,
    addName: String,
    removeLabel: String,
    removeName: String,
    private val onSelectionChanged: (T?) -> Unit
) : JPanel(BorderLayout(8, 0)) {

    private val listModel = DefaultListModel<T>()
    private val list = JList(listModel)

    init {
        items.forEach { listModel.addElement(it) }

        list.name = listName
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : DefaultListCellRenderer() {
            @Suppress("UNCHECKED_CAST")
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val label = component as JLabel
                label.text = if (value == null) "" else labelOf(value as T)
                return component
            }
        }
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) onSelectionChanged(selected())
        }

        val buttonRow = buttons(addLabel, addName, removeLabel, removeName)

        val master = JPanel(BorderLayout())
        master.add(JScrollPane(list), BorderLayout.CENTER)
        master.add(buttonRow, BorderLayout.SOUTH)
        // BorderLayout hands its WEST child exactly the child's preferred width, never more, so
        // a width fixed without looking at the row underneath simply clips it — and the second
        // button is the one that loses, reaching the screen as "Remove m…".
        //
        // The row is measured rather than given a bigger constant: both labels come from the
        // bundle, so a width that fits in English clips in a language whose words are longer,
        // and the IDE renders in a font this JVM does not have. A constant chosen today is a
        // constant wrong on someone else's machine.
        master.preferredSize = Dimension(maxOf(LIST_WIDTH, buttonRow.preferredSize.width), LIST_HEIGHT)

        add(master, BorderLayout.WEST)
        add(detailForm, BorderLayout.CENTER)
    }

    /** The item currently selected, or null when the selection is empty. */
    fun selected(): T? = items.getOrNull(list.selectedIndex)

    /** Replaces the selected item with [item], keeping the list label in sync. */
    fun replaceSelected(item: T) {
        val index = list.selectedIndex
        if (index < 0 || index >= items.size) return
        items[index] = item
        listModel.set(index, item)
    }

    /** Selects the first item, if there is one. */
    fun selectFirst() {
        if (items.isNotEmpty()) list.selectedIndex = 0
    }

    private fun buttons(addLabel: String, addName: String, removeLabel: String, removeName: String): JPanel {
        val addButton = JButton(addLabel)
        addButton.name = addName
        addButton.addActionListener { addItem() }

        val removeButton = JButton(removeLabel)
        removeButton.name = removeName
        removeButton.addActionListener { removeSelected() }

        // FlowLayout rather than BoxLayout: the two buttons sat edge to edge with no gap and no
        // margin, which is most of why they read as flat text rather than as a pair of buttons.
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, BUTTON_GAP, BUTTON_GAP))
        panel.add(addButton)
        panel.add(removeButton)
        return panel
    }

    private companion object {
        /** Width the item list gets whenever the buttons under it need no more. */
        const val LIST_WIDTH = 200
        const val LIST_HEIGHT = 200

        /** Gap around and between the two buttons, so they read as controls and not as text. */
        const val BUTTON_GAP = 4
    }

    private fun addItem() {
        val item = newItem()
        items.add(item)
        listModel.addElement(item)
        list.selectedIndex = items.size - 1
    }

    private fun removeSelected() {
        val index = list.selectedIndex
        if (index < 0 || index >= items.size) return
        items.removeAt(index)
        listModel.remove(index)
        // Dropping a row can leave the neighbour selected at the very same index, which fires
        // no selection event and would leave the form showing the item that is now gone.
        list.clearSelection()
        val next = minOf(index, items.size - 1)
        if (next < 0) onSelectionChanged(null) else list.selectedIndex = next
    }
}
