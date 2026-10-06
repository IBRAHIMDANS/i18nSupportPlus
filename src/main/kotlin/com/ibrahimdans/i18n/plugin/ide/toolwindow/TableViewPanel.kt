package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

/** Input map keys for the two shortcuts the table binds on itself. */
private const val ACTION_EDIT = "i18n.table.edit"
private const val ACTION_OPEN_FILE = "i18n.table.openFile"

/**
 * Panel displaying translations in a flat table format.
 * Columns: "Key" + one column per visible locale + "Usage", with a "Namespace" column in
 * front of the key while the rows span several namespaces (see
 * [TableViewModel.showsNamespaceColumn]). The key cell always holds the full key, prefix
 * included — every action reads it from there — and only *displays* it without the prefix
 * when the Namespace column carries it: under *All namespaces* the rows used to run as one
 * flow of `auth:…`, `common:…` with nothing marking where one namespace ended.
 * Includes a namespace combo box to filter rows by namespace prefix, and a status combo keeping
 * only the keys missing or empty in a shown locale, or found unused by the last scan.
 *
 * Every cell state is written out — an icon and a word — and only *then* tinted: a background
 * shade was the sole carrier of "missing" and "empty", which a greyscale screen, a colour
 * vision deficiency or a theme flattening both turns into three states that read as one.
 *
 * Locale cells are editable in place: an edit writes straight to the matching
 * translation file (one undo step per cell) and creates the entry when the
 * locale doesn't have it yet. On failure the previous value is restored and
 * an error dialog is shown. The key column stays read-only (renaming is
 * RenameI18nKeyHandler's job); double-clicking it opens the edit dialog.
 *
 * Keyboard: Enter edits the selected row, F4 opens the translation file it comes from.
 * Right-clicking a row offers both, plus deleting the key when the scan found it unused.
 * Right-clicking the header picks which locale columns are shown, which is what keeps the
 * table readable past four locales in a docked panel. The hidden locales are kept in the
 * workspace, per module, by [ToolWindowViewState].
 *
 * Scanning for orphan keys is [com.ibrahimdans.i18n.plugin.ide.actions.ScanOrphanKeysAction],
 * reachable from the tool window toolbar: the panel used to carry its own button in a
 * home-made filter bar, a third grammar of action next to the toolbar above it.
 *
 * When [moduleConfig] is non-null, only translations from that module are shown.
 */
class TableViewPanel(private val project: Project, private val moduleConfig: ModuleConfig? = null) : JPanel(BorderLayout()) {

    private val viewModel = TableViewModel()

    /** Where the hidden locales outlive the panel; `moduleConfig == null` is the project scope. */
    private val viewState = ToolWindowViewState.getInstance(project)
    private val tableModel = object : DefaultTableModel() {
        // Editable: locale columns only — not "Namespace"/"Key" (leading) nor "Usage" (last).
        override fun isCellEditable(row: Int, column: Int): Boolean =
            column in leadingColumns until columnCount - 1

        override fun setValueAt(aValue: Any?, row: Int, column: Int) {
            if (!isCellEditable(row, column)) {
                super.setValueAt(aValue, row, column)
                return
            }
            val newValue = aValue?.toString() ?: ""
            val oldValue = getValueAt(row, column)?.toString() ?: ""
            if (newValue == oldValue) return
            val key = getValueAt(row, keyColumn) as? String ?: return
            val locale = getColumnName(column)

            // moduleConfig is mandatory here: it scopes the write to the same module
            // the rows were loaded from (see TableViewModel.saveValue).
            if (viewModel.saveValue(project, key, locale, newValue, moduleConfig)) {
                super.setValueAt(newValue, row, column)
                // Keep the row cache in sync so filtering/rebuilds show the new value.
                allRows = allRows.map {
                    if (it.key == key) it.copy(values = it.values + (locale to newValue)) else it
                }
            } else {
                Messages.showErrorDialog(
                    project,
                    PluginBundle.message("toolwindow.table.edit.failed", key, locale),
                    PluginBundle.message("toolwindow.table.edit.failed.title")
                )
            }
        }
    }
    private val table = JBTable(tableModel)

    /** Every locale the module owns, whether shown or not. */
    private var locales: List<String> = emptyList()

    /**
     * The locales the user hid from the header menu, restricted to [locales] once loaded.
     * The saved set is the reference: see [refresh] for why this one is never written back as is.
     */
    private var hiddenLocales: Set<String> = viewState.hiddenLocales(moduleConfig)

    /** The locales currently laid out as columns, i.e. [locales] minus [hiddenLocales]. */
    private var shownLocales: List<String> = emptyList()

    /**
     * How many columns precede the locales: the key alone, or Namespace + Key. Every column
     * index of the panel derives from it, so laying the Namespace column out or not moves
     * nothing else.
     */
    private var leadingColumns: Int = 1
    private val keyColumn: Int get() = leadingColumns - 1

    private var allRows: List<TranslationRow> = emptyList()

    /** The configuration the rows were loaded under; what the default group is called depends on it. */
    private var config: Config = Config()
    private var currentFilter: String = ""
    private var currentNamespace: NamespaceFilter = NamespaceFilter.All
    private var currentStatus: StatusFilter = StatusFilter.ALL

    /**
     * Empties the tool window's search field, which owns the query: the field then filters the
     * table back through its usual path. Set by the tool window; a lone panel has no field.
     */
    var onClearSearch: () -> Unit = {}
    private var scanning: Boolean = false

    /** Loads started by [refresh] and not finished yet; the table paints busy while any is. */
    private var loading: Int = 0

    /** True while a usage scan is running, so the action does not queue a second one. */
    val isScanning: Boolean get() = scanning

    // The combo holds the filters themselves; the renderer is the only thing that turns one into
    // text, so a label can be translated without touching what the filter compares.
    private val namespaceCombo = JComboBox(arrayOf<NamespaceFilter>(NamespaceFilter.All)).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean
            ): Component = super.getListCellRendererComponent(
                list, (value as? NamespaceFilter)?.label(config) ?: value, index, selected, focused
            )
        }
    }

    // Same split as the namespace combo: the entries are StatusFilter values, the renderer alone
    // reads their label. A JComboBox cannot disable one entry, so *Unused* before a scan is drawn
    // disabled with a tooltip saying what to run, and picking it is undone by the listener.
    private val statusCombo = JComboBox(StatusFilter.entries.toTypedArray()).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean
            ): Component {
                val filter = value as? StatusFilter
                val component = super.getListCellRendererComponent(list, filter?.label ?: value, index, selected, focused)
                val available = filter == null || viewModel.isStatusFilterAvailable(filter, allRows)
                isEnabled = available
                toolTipText = if (available) null else PluginBundle.message("toolwindow.table.status.orphan.unavailable")
                return component
            }
        }
    }

    init {
        // Not AUTO_RESIZE_ALL_COLUMNS: that split the viewport in equal shares, so the key
        // column — the longest text of the table — got no more room than "Usage". Columns now
        // keep the widths TableViewModel.columnWidths gives them, stay draggable, and the
        // table scrolls sideways instead of crushing every column past the fourth locale.
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        // Cell rather than row selection: which *column* is selected is what tells Enter
        // whether to start the in-place editor and F4 which locale's file to open. With row
        // selection alone JTable reports no column at all, and both would have to guess.
        table.cellSelectionEnabled = true
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // Only the read-only key column opens the edit dialog: on locale
                // columns a double-click starts the in-place cell editor instead.
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1 && table.columnAtPoint(e.point) in 0 until leadingColumns) {
                    editSelectedRow()
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
        })
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showLocaleMenu(e)
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showLocaleMenu(e)
            }
        })
        registerShortcuts()

        namespaceCombo.addActionListener {
            currentNamespace = namespaceCombo.selectedItem as? NamespaceFilter ?: NamespaceFilter.All
            applyFilters()
        }
        statusCombo.addActionListener {
            val picked = statusCombo.selectedItem as? StatusFilter ?: StatusFilter.ALL
            if (!viewModel.isStatusFilterAvailable(picked, allRows)) {
                // Drawn disabled, but a JComboBox still lets it be selected: put the previous back.
                statusCombo.selectedItem = currentStatus
                return@addActionListener
            }
            currentStatus = picked
            applyFilters()
        }

        // The namespace label and combo come first: they stay the bar's first JLabel and JComboBox.
        val statusBar = JPanel(BorderLayout()).apply {
            add(JLabel(" " + PluginBundle.message("toolwindow.table.status.label") + " "), BorderLayout.WEST)
            add(statusCombo, BorderLayout.CENTER)
        }
        val filterBar = JPanel(BorderLayout()).apply {
            add(JLabel(PluginBundle.message("toolwindow.table.namespace.label") + " "), BorderLayout.WEST)
            add(namespaceCombo, BorderLayout.CENTER)
            add(statusBar, BorderLayout.EAST)
        }

        add(filterBar, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        updateEmptyText()
    }

    /**
     * Enter edits, F4 opens the file — the two gestures the panel only offered through the
     * mouse. Bound on the table's own `WHEN_FOCUSED` map, so an active cell editor (which
     * holds the focus itself) still commits on Enter rather than reopening a dialog.
     */
    private fun registerShortcuts() {
        table.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTION_EDIT)
        table.actionMap.put(ACTION_EDIT, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = editSelectedRow()
        })
        table.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), ACTION_OPEN_FILE)
        table.actionMap.put(ACTION_OPEN_FILE, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = openSelectedRowFile()
        })
    }

    /**
     * Reloads translation data and rebuilds the table, keeping the usage counts already found
     * for the keys that are still there (see [TableViewModel.mergeUsages]).
     *
     * This is what [TranslationChangeWatcher] runs after every translation file change, the
     * table's own edits included, so dropping the counts here threw away the scan at the first
     * corrected value. The toolbar Refresh button lands here too and keeps them as well, on
     * purpose: it reloads the *translation files*, while a count reflects the *source code*,
     * which only a scan reads. Starting the counts over is the Scan action's job — it recounts
     * every key — so Refresh does not need a second meaning, and the watcher and the button
     * cannot disagree about what the Usage column shows.
     */
    fun refresh() {
        setLoading(+1)
        ApplicationManager.getApplication().executeOnPooledThread {
            // A failed or cancelled load must not leave the table painting busy for good.
            try {
                load()
            } finally {
                ApplicationManager.getApplication().invokeLater { setLoading(-1) }
            }
        }
    }

    /**
     * Counted rather than flagged: the watcher and the toolbar can start a second load before
     * the first ends, and the first to finish must not clear the busy state of the other.
     * Runs on the EDT, where [refresh] is called.
     */
    private fun setLoading(delta: Int) {
        loading += delta
        table.setPaintBusy(loading > 0)
        updateEmptyText()
    }

    private fun load() {
        val rows = viewModel.loadRows(project, moduleConfig)
        config = Settings.getInstance(project).config()
        val discovered = viewModel.getLocales(project, moduleConfig)
        locales = discovered
        val namespaces = viewModel.namespaceFilters(rows)

        ApplicationManager.getApplication().invokeLater {
            // Merged on the EDT, where the in-place edit and the scan also write allRows:
            // reading it from the pooled thread could merge against a stale list.
            allRows = viewModel.mergeUsages(rows, allRows)
            // Restricted to the locales loaded now, for this load only — never written back:
            // a locale missing for one reload (a file being renamed, a branch switch) would
            // lose its hidden state for good. Read from the saved set each time, so it is
            // hidden again the moment it comes back.
            hiddenLocales = viewModel.hiddenAmong(discovered, viewState.hiddenLocales(moduleConfig))
            // Unused cannot outlive the counts it reads (the panel never drops them today,
            // but nothing else guarantees it).
            if (!viewModel.isStatusFilterAvailable(currentStatus, allRows)) statusCombo.selectedItem = StatusFilter.ALL
            updateNamespaceCombo(namespaces)
            applyFilters()
        }
    }

    /**
     * Applies a text filter to the table without reloading translation data.
     * Pass an empty string to clear the filter.
     */
    fun applyFilter(query: String) {
        currentFilter = query
        applyFilters()
    }

    private fun applyFilters() {
        val shown = viewModel.visibleLocales(locales, hiddenLocales)
        val filtered = viewModel.visibleRows(allRows, currentFilter, currentNamespace, currentStatus, shown)
        val withNamespace = viewModel.showsNamespaceColumn(currentNamespace, viewModel.namespaceFilters(allRows))
        rebuildTable(filtered, shown, withNamespace)
        updateEmptyText()
    }

    /**
     * What the table says when it shows no row: that it is loading, that the filters match
     * nothing — naming them, with a link clearing them all — or that there is no key at all.
     * Swing's generic text told none of these apart.
     */
    private fun updateEmptyText() {
        val emptyText = table.emptyText
        val query = currentFilter.trim()
        val namespace = if (currentNamespace == NamespaceFilter.All) null else currentNamespace.label(config)
        when {
            loading > 0 && allRows.isEmpty() -> emptyText.text = PluginBundle.message("toolwindow.table.empty.loading")
            query.isEmpty() && namespace == null && currentStatus == StatusFilter.ALL ->
                emptyText.text = PluginBundle.message("toolwindow.table.empty.none")
            else -> {
                val noMatch = when {
                    namespace == null && query.isEmpty() -> PluginBundle.message("toolwindow.table.empty.filters")
                    namespace == null -> PluginBundle.message("toolwindow.table.empty.query", query)
                    query.isEmpty() -> PluginBundle.message("toolwindow.table.empty.namespace", namespace)
                    else -> PluginBundle.message("toolwindow.table.empty.query.namespace", query, namespace)
                }
                emptyText.text = if (currentStatus == StatusFilter.ALL) noMatch
                else PluginBundle.message("toolwindow.table.empty.status", noMatch, currentStatus.label)
                emptyText.appendSecondaryText(
                    PluginBundle.message("toolwindow.table.empty.clear"),
                    SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES
                ) { clearFilters() }
            }
        }
    }

    /**
     * Back to every namespace, every status and no query; each combo's listener re-applies the
     * filters, and the search field re-applies the query. Internal for the tests: the empty text
     * keeps the link's listener out of reach.
     */
    internal fun clearFilters() {
        namespaceCombo.selectedItem = NamespaceFilter.All
        statusCombo.selectedItem = StatusFilter.ALL
        if (currentFilter.isNotEmpty()) onClearSearch()
    }

    private fun updateNamespaceCombo(items: List<NamespaceFilter>) {
        val selected = currentNamespace
        namespaceCombo.model = DefaultComboBoxModel(items.toTypedArray())
        // Restore selection if still valid, otherwise reset to "All"
        if (items.contains(selected)) namespaceCombo.selectedItem = selected
        else currentNamespace = NamespaceFilter.All
    }

    private fun rebuildTable(rows: List<TranslationRow>, locales: List<String>, withNamespace: Boolean = false) {
        shownLocales = locales
        leadingColumns = if (withNamespace) 2 else 1
        val columnNames = viewModel.columnNames(locales, withNamespace).toTypedArray()
        val data = rows.map { viewModel.rowCells(it, locales, withNamespace, config).toTypedArray() }.toTypedArray()

        tableModel.setDataVector(data, columnNames)

        val translationRenderer = TranslationCellRenderer(leadingColumns, locales.size, viewModel)
        val usageRenderer = UsageCellRenderer(viewModel)
        val usageColIdx = leadingColumns + locales.size
        val widths = viewModel.columnWidths(locales.size, withNamespace)

        for (i in 0 until table.columnCount) {
            val column = table.columnModel.getColumn(i)
            column.cellRenderer = if (i == usageColIdx) usageRenderer else translationRenderer
            widths.getOrNull(i)?.let { column.preferredWidth = it }
        }

        val sorter = TableRowSorter(tableModel)
        table.rowSorter = sorter
        if (tableModel.columnCount > 0) {
            // Namespace first, then the full key: the rows of one namespace stay together, and
            // that is what lets the column read as group boundaries rather than as a label.
            if (withNamespace) sorter.setComparator(0, viewModel.namespaceOrder(config))
            sorter.sortKeys = (0 until leadingColumns).map { RowSorter.SortKey(it, SortOrder.ASCENDING) }
        }
    }

    // ── Row actions ───────────────────────────────────────────────────────────

    /**
     * Edits the selected row: the in-place editor on a locale column, the translation
     * dialog anywhere else — the key column is read-only, so it has nothing else to offer.
     */
    private fun editSelectedRow() {
        val row = table.selectedRow
        if (row < 0) return
        val column = table.selectedColumn
        if (localeAt(column) != null) {
            table.editCellAt(row, column)
            table.editorComponent?.requestFocusInWindow()
            return
        }
        val key = table.getValueAt(row, keyColumn) as? String ?: return
        val dialog = TranslationDialog(project, buildFullKey(key), Mode.EDIT)
        if (dialog.showAndGet()) {
            refresh()
        }
    }

    /**
     * Opens the translation file the selected row comes from, at the key itself.
     * The locale is the selected column when one is selected, otherwise the first shown —
     * a row always has a file behind it, and the panel used to give no way to reach it.
     */
    private fun openSelectedRowFile() {
        val row = table.selectedRow
        if (row < 0) return
        val key = table.getValueAt(row, keyColumn) as? String ?: return
        val locale = localeAt(table.selectedColumn) ?: shownLocales.firstOrNull() ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            val target = ReadAction.compute<Pair<VirtualFile, Int>?, RuntimeException> {
                viewModel.locate(project, key, locale, moduleConfig)
            }
                ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                OpenFileDescriptor(project, target.first, target.second).navigate(true)
            }
        }
    }


    /** The locale [column] displays, or null when it is a leading or the usage column. */
    private fun localeAt(column: Int): String? = shownLocales.getOrNull(column - leadingColumns)

    // ── Context menu ──────────────────────────────────────────────────────────

    private fun showContextMenu(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row < 0) return
        // A right-click inside the selection keeps it, so that several rows can be deleted at once.
        if (!table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row)
            val column = table.columnAtPoint(e.point)
            if (column >= 0) table.setColumnSelectionInterval(column, column)
        }

        val selected = table.selectedRows.toList().mapNotNull { selectedRow ->
            val key = table.getValueAt(selectedRow, keyColumn) as? String ?: return@mapNotNull null
            key to (table.getValueAt(selectedRow, leadingColumns + shownLocales.size) as? Int ?: -1)
        }
        val orphanKeys = OrphanKeyDeleter.orphanKeys(selected, viewModel)

        val menu = JPopupMenu()
        menu.add(JMenuItem(PluginBundle.message("toolwindow.table.edit.key")).apply {
            addActionListener { editSelectedRow() }
        })
        menu.add(JMenuItem(PluginBundle.message("toolwindow.table.open.file")).apply {
            addActionListener { openSelectedRowFile() }
        })
        menu.addSeparator()
        // Non-orphan rows of the selection are left out, and the label counts only what is deleted.
        val deleteLabel = if (orphanKeys.size > 1) {
            PluginBundle.message("toolwindow.table.delete.orphans", orphanKeys.size)
        } else {
            PluginBundle.message("toolwindow.table.delete.orphan")
        }
        menu.add(JMenuItem(deleteLabel).apply {
            // Shown even when it does not apply, disabled: hiding it made the entry
            // impossible to discover from a row that had never been scanned.
            isEnabled = orphanKeys.isNotEmpty()
            if (orphanKeys.isEmpty()) toolTipText = PluginBundle.message("toolwindow.table.no.action")
            addActionListener { deleteOrphanKeys(orphanKeys) }
        })
        menu.show(e.component, e.x, e.y)
    }

    /**
     * Header menu picking which locale columns are laid out. Six locales in a docked panel
     * left no column readable; hiding the ones not being worked on is the way out that does
     * not lose any data.
     */
    private fun showLocaleMenu(e: MouseEvent) {
        if (locales.isEmpty()) return
        val menu = JPopupMenu()
        menu.add(JMenuItem(PluginBundle.message("toolwindow.table.columns.title")).apply { isEnabled = false })
        menu.addSeparator()
        for (locale in locales) {
            menu.add(JCheckBoxMenuItem(locale, locale !in hiddenLocales).apply {
                addActionListener {
                    hiddenLocales = viewModel.toggleLocale(locales, hiddenLocales, locale)
                    // Saved locales not loaded right now are kept: the user toggled this one only.
                    val absent = viewState.hiddenLocales(moduleConfig).filterNot { it in locales }
                    viewState.setHiddenLocales(moduleConfig, hiddenLocales + absent)
                    applyFilters()
                }
            })
        }
        menu.show(e.component, e.x, e.y)
    }

    // ── Scan Orphans ──────────────────────────────────────────────────────────

    /**
     * Runs orphan detection in background. Updates allRows with usage counts,
     * then rebuilds the table on the EDT.
     *
     * Public because the trigger is [com.ibrahimdans.i18n.plugin.ide.actions.ScanOrphanKeysAction]
     * now, in the tool window toolbar, rather than a button of this panel's own.
     */
    fun scanOrphans() {
        if (scanning) return
        scanning = true
        val rowsToScan = allRows.toList()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, PluginBundle.message("toolwindow.table.scan.progress.title"), false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = PluginBundle.message("toolwindow.table.scan.progress.text")
                val scanned = viewModel.countUsages(project, rowsToScan)

                ApplicationManager.getApplication().invokeLater {
                    // Merge scanned usage counts back into allRows, preserving original order
                    val usageByKey = scanned.associate { it.key to it.usageCount }
                    allRows = allRows.map { row ->
                        row.copy(usageCount = usageByKey[row.key] ?: row.usageCount)
                    }
                    applyFilters()
                    scanning = false
                }
            }
        })
    }

    // ── Delete orphan key ─────────────────────────────────────────────────────

    /**
     * Deletes the keys from all matching localization sources using CompositeKeyResolver,
     * in one undoable command, then refreshes the table once the deletion has run.
     */
    private fun deleteOrphanKeys(keyStrings: List<String>) {
        val fullKeys = keyStrings.map(::buildFullKey)
        // Scoped to this panel's module: without it the key is also deleted from
        // another module's file sharing the same namespace.
        // The deletion completes asynchronously (the source lookup runs off the EDT):
        // refreshing right after the call would reload the rows before the key is gone.
        OrphanKeyDeleter(project, moduleConfig).delete(fullKeys, onFinished = ::refresh)
    }

    /**
     * Builds a FullKey from a flat key string, handling optional namespace prefix.
     * Delegates to the synchronizer's parser, which the in-place edit already goes
     * through — the panel used to carry a second copy that kept empty segments.
     */
    private fun buildFullKey(keyString: String): FullKey =
        KeysSynchronizer().buildFullKey(keyString, Settings.getInstance(project).config())
}
