package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.tree.Tree
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
import com.intellij.psi.PsiElement
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

// Not `const`: these come from the bundle now.
private val USAGE_COLUMN_NAME = PluginBundle.message("toolwindow.table.column.usage")

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
 * Includes a namespace combo box to filter rows by namespace prefix.
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
 * table readable past four locales in a docked panel.
 *
 * Scanning for orphan keys is [com.ibrahimdans.i18n.plugin.ide.actions.ScanOrphanKeysAction],
 * reachable from the tool window toolbar: the panel used to carry its own button in a
 * home-made filter bar, a third grammar of action next to the toolbar above it.
 *
 * When [moduleConfig] is non-null, only translations from that module are shown.
 */
class TableViewPanel(private val project: Project, private val moduleConfig: ModuleConfig? = null) : JPanel(BorderLayout()) {

    private val viewModel = TableViewModel()
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

    /** The locales the user hid from the header menu. */
    private var hiddenLocales: Set<String> = emptySet()

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
    private var scanning: Boolean = false

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

        val filterBar = JPanel(BorderLayout()).apply {
            add(JLabel(PluginBundle.message("toolwindow.table.namespace.label") + " "), BorderLayout.WEST)
            add(namespaceCombo, BorderLayout.CENTER)
        }

        add(filterBar, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
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
     * Reloads translation data and rebuilds the table.
     */
    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val rows = viewModel.loadRows(project, moduleConfig)
            config = Settings.getInstance(project).config()
            val discovered = viewModel.getLocales(project, moduleConfig)
            locales = discovered
            allRows = rows
            val namespaces = viewModel.namespaceFilters(rows)

            ApplicationManager.getApplication().invokeLater {
                // A locale that disappeared from the project must not stay hidden forever.
                hiddenLocales = hiddenLocales.filter { it in discovered }.toSet()
                updateNamespaceCombo(namespaces)
                applyFilters()
            }
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
        val filtered = viewModel.filter(currentFilter, viewModel.filterByNamespace(currentNamespace, allRows))
        val withNamespace = viewModel.showsNamespaceColumn(currentNamespace, viewModel.namespaceFilters(allRows))
        rebuildTable(filtered, viewModel.visibleLocales(locales, hiddenLocales), withNamespace)
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
        val leadingNames = listOfNotNull(
            PluginBundle.message("toolwindow.table.column.namespace").takeIf { withNamespace },
            PluginBundle.message("toolwindow.table.column.key"),
        )
        val columnNames = leadingNames.toTypedArray() + locales.toTypedArray() + USAGE_COLUMN_NAME
        // The usage cell holds the count itself, not a rendered string: the renderer decides
        // how it reads, and the context menu no longer has to sniff a label for a leading "0".
        val data = rows.map { row ->
            val cells = ArrayList<Any>(locales.size + leadingColumns + 1)
            if (withNamespace) cells.add(viewModel.namespaceLabel(row.key, config))
            cells.add(row.key)
            locales.mapTo(cells) { locale -> row.values[locale] ?: "" }
            cells.add(row.usageCount)
            cells.toArray()
        }.toTypedArray()

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
            val target = ReadAction.compute<Pair<VirtualFile, Int>?, RuntimeException> { locate(key, locale) }
                ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                OpenFileDescriptor(project, target.first, target.second).navigate(true)
            }
        }
    }

    /**
     * The file and offset where [key] is declared for [locale]. Falls back to the deepest
     * segment that does resolve — a key present in one locale and not in another still opens
     * the right file, at its nearest parent, rather than nothing at all.
     * Must be called inside a read action.
     */
    private fun locate(key: String, locale: String): Pair<VirtualFile, Int>? {
        val source = viewModel.findSourceFor(project, key, locale, moduleConfig) ?: return null
        var node: Tree<PsiElement> = source.tree ?: return null
        for (segment in viewModel.keySegments(key, Settings.getInstance(project).config())) {
            node = node.findChild(segment) ?: break
        }
        val psi = node.value()
        val file = psi.containingFile?.virtualFile ?: return null
        return file to psi.textOffset
    }

    /** The locale [column] displays, or null when it is a leading or the usage column. */
    private fun localeAt(column: Int): String? = shownLocales.getOrNull(column - leadingColumns)

    // ── Context menu ──────────────────────────────────────────────────────────

    private fun showContextMenu(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row < 0) return
        table.setRowSelectionInterval(row, row)
        val column = table.columnAtPoint(e.point)
        if (column >= 0) table.setColumnSelectionInterval(column, column)

        val key = table.getValueAt(row, keyColumn) as? String ?: return
        val usageCount = table.getValueAt(row, leadingColumns + shownLocales.size) as? Int ?: -1
        val isOrphan = viewModel.usageStatus(usageCount) == UsageStatus.ORPHAN

        val menu = JPopupMenu()
        menu.add(JMenuItem(PluginBundle.message("toolwindow.table.edit.key")).apply {
            addActionListener { editSelectedRow() }
        })
        menu.add(JMenuItem(PluginBundle.message("toolwindow.table.open.file")).apply {
            addActionListener { openSelectedRowFile() }
        })
        menu.addSeparator()
        menu.add(JMenuItem(PluginBundle.message("toolwindow.table.delete.orphan")).apply {
            // Shown even when it does not apply, disabled: hiding it made the entry
            // impossible to discover from a row that had never been scanned.
            isEnabled = isOrphan
            if (!isOrphan) toolTipText = PluginBundle.message("toolwindow.table.no.action")
            addActionListener { deleteOrphanKey(key) }
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
     * Deletes a key from all matching localization sources using CompositeKeyResolver,
     * then refreshes the table once the deletion has run.
     */
    private fun deleteOrphanKey(keyString: String) {
        val fullKey = buildFullKey(keyString)
        // Scoped to this panel's module: without it the key is also deleted from
        // another module's file sharing the same namespace.
        // The deletion completes asynchronously (the source lookup runs off the EDT):
        // refreshing right after the call would reload the rows before the key is gone.
        OrphanKeyDeleter(project, moduleConfig).delete(fullKey, onFinished = ::refresh)
    }

    /**
     * Builds a FullKey from a flat key string, handling optional namespace prefix.
     * Delegates to the synchronizer's parser, which the in-place edit already goes
     * through — the panel used to carry a second copy that kept empty segments.
     */
    private fun buildFullKey(keyString: String): FullKey =
        KeysSynchronizer().buildFullKey(keyString, Settings.getInstance(project).config())
}
