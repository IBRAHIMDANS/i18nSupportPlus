package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.deletePropertyAndSeparator
import com.intellij.icons.AllIcons
import com.intellij.json.psi.JsonProperty
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.ui.JBColor
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
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

// Not `const`: these come from the bundle now.
private val USAGE_COLUMN_NAME = PluginBundle.message("toolwindow.table.column.usage")
private val NOT_SCANNED_TOOLTIP = PluginBundle.message("toolwindow.table.usage.not.scanned")
private val NOT_SCANNED_LABEL = PluginBundle.message("toolwindow.table.usage.pending")
private val ORPHAN_LABEL = PluginBundle.message("toolwindow.table.usage.orphan")
private val ORPHAN_TOOLTIP = PluginBundle.message("toolwindow.table.usage.orphan.tooltip")
private val DYNAMIC_LABEL = PluginBundle.message("toolwindow.table.usage.dynamic")
private val DYNAMIC_TOOLTIP = PluginBundle.message("toolwindow.table.usage.dynamic.tooltip")
private val MISSING_LABEL = PluginBundle.message("toolwindow.table.value.missing")
private val MISSING_TOOLTIP = PluginBundle.message("toolwindow.table.value.missing.tooltip")
private val BLANK_LABEL = PluginBundle.message("toolwindow.table.value.blank")
private val BLANK_TOOLTIP = PluginBundle.message("toolwindow.table.value.blank.tooltip")
internal const val DISPLAY_VALUE_MAX_LENGTH = 200

// The IDE's own file-colour tints rather than six invented RGB values: they are the palette
// themes already redefine, so the table follows a dark or high-contrast theme instead of
// fighting it. They only ever *reinforce* a state the cell also spells out in words.
private val MISSING_BACKGROUND = JBColor.namedColor("FileColor.Rose", JBColor.PINK)
private val BLANK_BACKGROUND = JBColor.namedColor("FileColor.Yellow", JBColor.YELLOW)
private val ORPHAN_FOREGROUND = JBColor.namedColor("Label.errorForeground", JBColor.RED)
private val NOT_SCANNED_FOREGROUND = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)

// Not the orphan red: the key is reachable, only not by a name written anywhere.
private val DYNAMIC_FOREGROUND = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)

/** Input map keys for the two shortcuts the table binds on itself. */
private const val ACTION_EDIT = "i18n.table.edit"
private const val ACTION_OPEN_FILE = "i18n.table.openFile"

/**
 * Normalizes a raw translation value for single-line table display:
 * collapses all whitespace runs (including newlines) to one space, trims,
 * and truncates to [maxLength] with an ellipsis. The raw value is meant
 * to stay available in the cell tooltip.
 */
internal fun displayValue(raw: String, maxLength: Int = DISPLAY_VALUE_MAX_LENGTH): String {
    val collapsed = raw.replace(Regex("\\s+"), " ").trim()
    return if (collapsed.length <= maxLength) collapsed else collapsed.take(maxLength) + "…"
}

/**
 * Panel displaying translations in a flat table format.
 * Columns: "Key" + one column per visible locale + "Usage".
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
        // Editable: locale columns only — not "Key" (column 0) nor "Usage" (last).
        override fun isCellEditable(row: Int, column: Int): Boolean =
            column in 1 until columnCount - 1

        override fun setValueAt(aValue: Any?, row: Int, column: Int) {
            if (!isCellEditable(row, column)) {
                super.setValueAt(aValue, row, column)
                return
            }
            val newValue = aValue?.toString() ?: ""
            val oldValue = getValueAt(row, column)?.toString() ?: ""
            if (newValue == oldValue) return
            val key = getValueAt(row, 0) as? String ?: return
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

    private var allRows: List<TranslationRow> = emptyList()
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
                list, (value as? NamespaceFilter)?.label ?: value, index, selected, focused
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
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1 && table.columnAtPoint(e.point) == 0) {
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
        rebuildTable(filtered, viewModel.visibleLocales(locales, hiddenLocales))
    }

    private fun updateNamespaceCombo(items: List<NamespaceFilter>) {
        val selected = currentNamespace
        namespaceCombo.model = DefaultComboBoxModel(items.toTypedArray())
        // Restore selection if still valid, otherwise reset to "All"
        if (items.contains(selected)) namespaceCombo.selectedItem = selected
        else currentNamespace = NamespaceFilter.All
    }

    private fun rebuildTable(rows: List<TranslationRow>, locales: List<String>) {
        shownLocales = locales
        val columnNames = arrayOf(PluginBundle.message("toolwindow.table.column.key")) + locales.toTypedArray() + USAGE_COLUMN_NAME
        // The usage cell holds the count itself, not a rendered string: the renderer decides
        // how it reads, and the context menu no longer has to sniff a label for a leading "0".
        val data = rows.map { row ->
            val cells = ArrayList<Any>(locales.size + 2)
            cells.add(row.key)
            locales.mapTo(cells) { locale -> row.values[locale] ?: "" }
            cells.add(row.usageCount)
            cells.toArray()
        }.toTypedArray()

        tableModel.setDataVector(data, columnNames)

        val translationRenderer = TranslationCellRenderer(locales.size)
        val usageRenderer = UsageCellRenderer()
        val usageColIdx = 1 + locales.size
        val widths = viewModel.columnWidths(locales.size)

        for (i in 0 until table.columnCount) {
            val column = table.columnModel.getColumn(i)
            column.cellRenderer = if (i == usageColIdx) usageRenderer else translationRenderer
            widths.getOrNull(i)?.let { column.preferredWidth = it }
        }

        val sorter = TableRowSorter(tableModel)
        table.rowSorter = sorter
        if (tableModel.columnCount > 0) {
            sorter.sortKeys = listOf(RowSorter.SortKey(0, SortOrder.ASCENDING))
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
        val key = table.getValueAt(row, 0) as? String ?: return
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
        val key = table.getValueAt(row, 0) as? String ?: return
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
        for (segment in viewModel.keySegments(key)) {
            node = node.findChild(segment) ?: break
        }
        val psi = node.value()
        val file = psi.containingFile?.virtualFile ?: return null
        return file to psi.textOffset
    }

    /** The locale [column] displays, or null when it is the key or the usage column. */
    private fun localeAt(column: Int): String? =
        if (column in 1..shownLocales.size) shownLocales[column - 1] else null

    // ── Context menu ──────────────────────────────────────────────────────────

    private fun showContextMenu(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row < 0) return
        table.setRowSelectionInterval(row, row)
        val column = table.columnAtPoint(e.point)
        if (column >= 0) table.setColumnSelectionInterval(column, column)

        val key = table.getValueAt(row, 0) as? String ?: return
        val usageCount = table.getValueAt(row, 1 + shownLocales.size) as? Int ?: -1
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
     * Deletes a key from all matching localization sources using CompositeKeyResolver.
     * Runs the PSI write inside a WriteCommandAction for undo support.
     */
    private fun deleteOrphanKey(keyString: String) {
        val fullKey = buildFullKey(keyString)
        // Scoped to this panel's module: without it the key is also deleted from
        // another module's file sharing the same namespace.
        val deleter = OrphanKeyDeleter(project, moduleConfig)
        deleter.delete(fullKey)
        // Refresh the table after deletion
        refresh()
    }

    /**
     * Builds a FullKey from a flat key string, handling optional namespace prefix.
     * Delegates to the synchronizer's parser, which the in-place edit already goes
     * through — the panel used to carry a second copy that kept empty segments.
     */
    private fun buildFullKey(keyString: String): FullKey = KeysSynchronizer().buildFullKey(keyString)

    // ── Cell Renderers ────────────────────────────────────────────────────────

    /**
     * Cell renderer for the key and locale columns.
     *
     * A locale cell says what it is — an icon and a word for a missing or an empty value,
     * the value itself otherwise — and the background tint only repeats it. Before, the tint
     * was the whole message: a cell with no entry and a cell holding `"   "` differed by two
     * shades of nothing, and neither was distinguishable from a translated cell in greyscale.
     *
     * [localeCount] is the number of locale columns (column 0 is "Key", columns 1..localeCount are locales).
     */
    private inner class TranslationCellRenderer(private val localeCount: Int) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val raw = value?.toString() ?: ""
            // DefaultTableCellRenderer reuses one component for every cell: whatever the
            // previous cell set has to be cleared, not merely overwritten on some branches.
            icon = null
            toolTipText = null
            if (!isSelected) background = table.background

            if (column <= 0 || column > localeCount) return component

            when (viewModel.valueStatus(raw)) {
                ValueStatus.MISSING -> {
                    text = MISSING_LABEL
                    icon = AllIcons.General.Error
                    toolTipText = MISSING_TOOLTIP
                    if (!isSelected) background = MISSING_BACKGROUND
                }

                ValueStatus.BLANK -> {
                    text = BLANK_LABEL
                    icon = AllIcons.General.Warning
                    toolTipText = BLANK_TOOLTIP
                    if (!isSelected) background = BLANK_BACKGROUND
                }

                // Values arrive verbatim from the translation files (newlines, indentation):
                // render a collapsed single line, keep the full raw value in the tooltip.
                ValueStatus.TRANSLATED -> {
                    text = displayValue(raw)
                    toolTipText = raw
                }
            }
            return component
        }
    }

    /**
     * Cell renderer for the "Usage" column, whose model value is the raw count.
     *
     * Never scanned, unused and used are three states, and the column used to separate them
     * by foreground colour alone — with `—` standing in for the first. Each now carries its
     * own icon and wording; the colour follows.
     */
    private inner class UsageCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val count = (value as? Int) ?: -1
            icon = null
            toolTipText = null
            if (!isSelected) {
                background = table.background
                foreground = table.foreground
            }

            when (viewModel.usageStatus(count)) {
                UsageStatus.NOT_SCANNED -> {
                    text = NOT_SCANNED_LABEL
                    icon = AllIcons.General.Information
                    toolTipText = NOT_SCANNED_TOOLTIP
                    if (!isSelected) foreground = NOT_SCANNED_FOREGROUND
                }

                UsageStatus.ORPHAN -> {
                    text = ORPHAN_LABEL
                    icon = AllIcons.General.Warning
                    toolTipText = ORPHAN_TOOLTIP
                    if (!isSelected) foreground = ORPHAN_FOREGROUND
                }

                UsageStatus.DYNAMIC -> {
                    text = DYNAMIC_LABEL
                    icon = AllIcons.General.Information
                    toolTipText = DYNAMIC_TOOLTIP
                    if (!isSelected) foreground = DYNAMIC_FOREGROUND
                }

                UsageStatus.USED -> text = count.toString()
            }
            return component
        }
    }
}

// ── OrphanKeyDeleter ──────────────────────────────────────────────────────────

/**
 * Resolves and deletes a translation key from the matching localization sources.
 * Implements [CompositeKeyResolver] to reuse the existing key resolution logic.
 *
 * [moduleConfig] must be the module the rows were loaded with: the namespace-based
 * lookup alone is not enough, since two modules commonly own a file with the same
 * namespace. Without the scope, deleting an orphan key from one module's table also
 * removed it from the other module's file.
 */
internal class OrphanKeyDeleter(
    private val project: Project,
    private val moduleConfig: ModuleConfig? = null,
) : CompositeKeyResolver<PsiElement> {

    fun delete(fullKey: FullKey) {
        val sourceService = project.service<LocalizationSourceService>()
        val namespaces = fullKey.allNamespaces()
        val sources = sourceService.findSources(namespaces, project)
            .ifEmpty { if (namespaces.isEmpty()) sourceService.findAllSources(project) else emptyList() }
            .let { found -> scopeToModule(found) }

        // Collect first, then delete everything in one WriteCommandAction:
        // a single undo restores the key in every locale. The deletion targets
        // the whole property (not just its value, which used to leave a
        // dangling `"key":`) and removes the separating comma with it.
        val properties = sources.mapNotNull { source ->
            val ref = resolveCompositeKey(fullKey.compositeKey, source) ?: return@mapNotNull null
            if (ref.unresolved.isNotEmpty() || ref.element == null) return@mapNotNull null
            PsiTreeUtil.getParentOfType(ref.element.value(), JsonProperty::class.java, YAMLKeyValue::class.java)
        }
        if (properties.isEmpty()) return

        WriteCommandAction.runWriteCommandAction(project, PluginBundle.message("toolwindow.table.delete.command"), null, {
            properties.forEach { if (it.isValid) deletePropertyAndSeparator(it) }
        })
    }

    /** Keeps only the sources belonging to [moduleConfig], like TranslationDataLoader does for reads. */
    private fun scopeToModule(sources: List<LocalizationSource>): List<LocalizationSource> {
        val root = moduleConfig?.rootDirectory?.trimEnd('/')
        if (root.isNullOrBlank()) return sources
        return sources.filter { it.displayPath.startsWith(root) }
    }
}
