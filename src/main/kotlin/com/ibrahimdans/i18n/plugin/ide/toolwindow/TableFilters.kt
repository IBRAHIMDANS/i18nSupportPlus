package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.StatusText
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel

/**
 * The Namespace and Status filters of the Table View, the filter each one holds, and what the
 * table says when they leave no row.
 *
 * [config] and [sources] name the default group in the namespace entries, [rows] tells which
 * status filters can be picked yet — *Unused* needs a scan — and [onChanged] runs every time the
 * user picks another filter, for the table to be filtered again.
 */
internal class TableFilters(
    private val viewModel: TableViewModel,
    private val config: () -> Config,
    private val sources: () -> List<LocalizationSource>,
    private val rows: () -> List<TranslationRow>,
    private val onChanged: () -> Unit,
) {

    /** The namespace the rows are restricted to. */
    var namespace: NamespaceFilter = NamespaceFilter.All
        private set

    /** The status the rows are restricted to. */
    var status: StatusFilter = StatusFilter.ALL
        private set

    // The combo holds the filters themselves; the renderer is the only thing that turns one into
    // text, so a label can be translated without touching what the filter compares.
    private val namespaceCombo = JComboBox(arrayOf<NamespaceFilter>(NamespaceFilter.All)).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean
            ): Component = super.getListCellRendererComponent(
                list, (value as? NamespaceFilter)?.label(config(), sources()) ?: value, index, selected, focused
            )
        }
    }

    // Same split as the namespace combo: the entries are StatusFilter values, the renderer alone
    // reads their label. A JComboBox cannot disable one entry, so *Unused* before a scan is drawn
    // disabled with a tooltip saying what to run, and picking it is undone by the listener.
    // The review filters join the entries only where reviews are on: see updateStatusEntries.
    private val statusCombo = JComboBox(viewModel.statusFilters(TableReviews.OFF).toTypedArray()).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean
            ): Component {
                val filter = value as? StatusFilter
                val component = super.getListCellRendererComponent(list, filter?.label ?: value, index, selected, focused)
                val available = filter == null || viewModel.isStatusFilterAvailable(filter, rows())
                isEnabled = available
                toolTipText = if (available) null else PluginBundle.message("toolwindow.table.status.orphan.unavailable")
                return component
            }
        }
    }

    /**
     * The Namespace and Status filters. Laid out above the table, unless the tool window lends it
     * a place in its own top bar — see [ShellContent.fitToWidth]. The namespace label and combo
     * come first: they stay the bar's first JLabel and JComboBox.
     */
    val bar: JComponent = JPanel(BorderLayout()).apply {
        add(JLabel(PluginBundle.message("toolwindow.table.namespace.label") + " "), BorderLayout.WEST)
        add(namespaceCombo, BorderLayout.CENTER)
        add(
            JPanel(BorderLayout()).apply {
                add(JLabel(" " + PluginBundle.message("toolwindow.table.status.label") + " "), BorderLayout.WEST)
                add(statusCombo, BorderLayout.CENTER)
            },
            BorderLayout.EAST
        )
    }

    init {
        namespaceCombo.addActionListener {
            namespace = namespaceCombo.selectedItem as? NamespaceFilter ?: NamespaceFilter.All
            onChanged()
        }
        statusCombo.addActionListener {
            val picked = statusCombo.selectedItem as? StatusFilter ?: StatusFilter.ALL
            if (!viewModel.isStatusFilterAvailable(picked, rows())) {
                // Drawn disabled, but a JComboBox still lets it be selected: put the previous back.
                statusCombo.selectedItem = status
                return@addActionListener
            }
            status = picked
            onChanged()
        }
    }

    /** Back to every namespace and every status; each combo's listener re-applies the filters. */
    fun clear() {
        namespaceCombo.selectedItem = NamespaceFilter.All
        statusCombo.selectedItem = StatusFilter.ALL
    }

    /**
     * Back to every status when the one picked no longer applies: *Unused* cannot outlive the
     * counts it reads (the panel never drops them today, but nothing else guarantees it).
     */
    fun resetUnavailableStatus() {
        if (!viewModel.isStatusFilterAvailable(status, rows())) statusCombo.selectedItem = StatusFilter.ALL
    }

    /**
     * Offers the review filters exactly while reviews are on. A review filter picked before the
     * folder went away falls back to every key; the combo's listener then re-applies the filters.
     */
    fun updateStatusEntries(reviews: TableReviews) {
        val items = viewModel.statusFilters(reviews)
        val model = statusCombo.model
        if ((0 until model.size).map { model.getElementAt(it) } == items) return
        val selected = status
        statusCombo.model = DefaultComboBoxModel(items.toTypedArray())
        statusCombo.selectedItem = if (selected in items) selected else StatusFilter.ALL
    }

    /** The status filters the combo offers right now. */
    fun statusEntries(): List<StatusFilter> =
        (0 until statusCombo.model.size).map { statusCombo.model.getElementAt(it) }

    /** Offers [items], keeping the namespace picked when it is still one of them. */
    fun updateNamespaces(items: List<NamespaceFilter>) {
        val selected = namespace
        namespaceCombo.model = DefaultComboBoxModel(items.toTypedArray())
        // Restore selection if still valid, otherwise reset to "All"
        if (items.contains(selected)) namespaceCombo.selectedItem = selected
        else namespace = NamespaceFilter.All
    }

    /**
     * What the table says when it shows no row: that it is [loading] with nothing yet, that the
     * filters and the [query] match nothing — naming them, with a link to [onClear] — or that
     * there is no key at all. Swing's generic text told none of these apart.
     */
    fun describeEmpty(emptyText: StatusText, query: String, loading: Boolean, hasRows: Boolean, onClear: () -> Unit) {
        val trimmed = query.trim()
        val namespaceLabel = if (namespace == NamespaceFilter.All) null else namespace.label(config(), sources())
        when {
            loading && !hasRows -> emptyText.text = PluginBundle.message("toolwindow.table.empty.loading")
            trimmed.isEmpty() && namespaceLabel == null && status == StatusFilter.ALL ->
                emptyText.text = PluginBundle.message("toolwindow.table.empty.none")
            else -> {
                val noMatch = when {
                    namespaceLabel == null && trimmed.isEmpty() -> PluginBundle.message("toolwindow.table.empty.filters")
                    namespaceLabel == null -> PluginBundle.message("toolwindow.table.empty.query", trimmed)
                    trimmed.isEmpty() -> PluginBundle.message("toolwindow.table.empty.namespace", namespaceLabel)
                    else -> PluginBundle.message("toolwindow.table.empty.query.namespace", trimmed, namespaceLabel)
                }
                emptyText.text = if (status == StatusFilter.ALL) noMatch
                else PluginBundle.message("toolwindow.table.empty.status", noMatch, status.label)
                emptyText.appendSecondaryText(
                    PluginBundle.message("toolwindow.table.empty.clear"),
                    SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES
                ) { onClear() }
            }
        }
    }
}
