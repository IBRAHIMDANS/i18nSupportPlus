package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.deletePropertyAndSeparator
import com.intellij.json.psi.JsonProperty
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.ui.JBColor
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

private const val ALL_NAMESPACES = "All namespaces"
private const val USAGE_COLUMN_NAME = "Usage"
private const val NOT_SCANNED_TOOLTIP = "Run Scan Orphans to compute usages"
internal const val DISPLAY_VALUE_MAX_LENGTH = 200

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
 * Columns: "Key" + one column per locale + "Usage".
 * Includes a namespace combo box to filter rows by namespace prefix.
 * Empty cells are highlighted with a warning color.
 * Locale cells are editable in place: an edit writes straight to the matching
 * translation file (one undo step per cell) and creates the entry when the
 * locale doesn't have it yet. On failure the previous value is restored and
 * an error dialog is shown. The key column stays read-only (renaming is
 * RenameI18nKeyHandler's job); double-clicking it opens the edit dialog.
 * A "Scan Orphans" button triggers background usage analysis.
 * Right-clicking a row with usage=0 offers a "Delete orphan key" option.
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
                    "Could not write '$key' for locale '$locale'. No matching translation file was found, or the file is not writable.",
                    "Edit Translation"
                )
            }
        }
    }
    private val table = JBTable(tableModel)
    private var locales: List<String> = emptyList()
    private var allRows: List<TranslationRow> = emptyList()
    private var currentFilter: String = ""
    private var currentNamespace: String = ALL_NAMESPACES

    private val namespaceCombo = JComboBox(arrayOf(ALL_NAMESPACES))
    private val scanButton = JButton("Scan Orphans").apply {
        toolTipText = "Count code usages for each key and fill the Usage column"
    }

    init {
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // Only the read-only key column opens the edit dialog: on locale
                // columns a double-click starts the in-place cell editor instead.
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1 && table.columnAtPoint(e.point) == 0) {
                    handleDoubleClick()
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
        })

        namespaceCombo.addActionListener {
            currentNamespace = namespaceCombo.selectedItem as? String ?: ALL_NAMESPACES
            applyFilters()
        }

        scanButton.addActionListener { scanOrphans() }

        val filterBar = JPanel(BorderLayout()).apply {
            add(JLabel("Namespace: "), BorderLayout.WEST)
            add(namespaceCombo, BorderLayout.CENTER)
            add(scanButton, BorderLayout.EAST)
        }

        add(filterBar, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
    }

    /**
     * Reloads translation data and rebuilds the table.
     */
    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val rows = viewModel.loadRows(project, moduleConfig)
            locales = viewModel.getLocales(project, moduleConfig)
            allRows = rows
            val namespaces = extractNamespaces(rows)

            ApplicationManager.getApplication().invokeLater {
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
        val filtered = viewModel.filter(currentFilter, filterByNamespace(currentNamespace, allRows))
        rebuildTable(filtered, locales)
    }

    private fun filterByNamespace(namespace: String, rows: List<TranslationRow>): List<TranslationRow> {
        if (namespace == ALL_NAMESPACES) return rows
        return rows.filter { row ->
            if (namespace == "(default)") !row.key.contains(':')
            else row.key.startsWith("$namespace:")
        }
    }

    /** Extracts distinct namespace prefixes from keys (part before ':'). */
    private fun extractNamespaces(rows: List<TranslationRow>): List<String> {
        val namespaces = rows.mapNotNull { row ->
            val colonIdx = row.key.indexOf(':')
            if (colonIdx > 0) row.key.substring(0, colonIdx) else null
        }.distinct().sorted()
        return if (rows.any { !it.key.contains(':') }) listOf("(default)") + namespaces else namespaces
    }

    private fun updateNamespaceCombo(namespaces: List<String>) {
        val selected = currentNamespace
        val items = listOf(ALL_NAMESPACES) + namespaces
        namespaceCombo.model = DefaultComboBoxModel(items.toTypedArray())
        // Restore selection if still valid, otherwise reset to "All"
        if (items.contains(selected)) namespaceCombo.selectedItem = selected
        else currentNamespace = ALL_NAMESPACES
    }

    private fun rebuildTable(rows: List<TranslationRow>, locales: List<String>) {
        val columnNames = arrayOf("Key") + locales.toTypedArray() + USAGE_COLUMN_NAME
        val data = rows.map { row ->
            val usageCell = when (row.usageCount) {
                -1 -> "—"
                0 -> "0 (orphan)"
                else -> row.usageCount.toString()
            }
            arrayOf(row.key) + locales.map { locale -> row.values[locale] ?: "" }.toTypedArray() + usageCell
        }.toTypedArray()

        tableModel.setDataVector(data, columnNames)

        val translationRenderer = TranslationCellRenderer(locales.size)
        val usageRenderer = UsageCellRenderer()
        val usageColIdx = 1 + locales.size

        for (i in 0 until table.columnCount) {
            table.columnModel.getColumn(i).cellRenderer =
                if (i == usageColIdx) usageRenderer else translationRenderer
        }

        // Narrow the Usage column
        if (usageColIdx < table.columnCount) {
            table.columnModel.getColumn(usageColIdx).preferredWidth = 90
            table.columnModel.getColumn(usageColIdx).maxWidth = 120
        }

        val sorter = TableRowSorter(tableModel)
        table.rowSorter = sorter
        if (tableModel.columnCount > 0) {
            sorter.sortKeys = listOf(RowSorter.SortKey(0, SortOrder.ASCENDING))
        }
    }

    // ── Double-click: open edit dialog ────────────────────────────────────────

    private fun handleDoubleClick() {
        val row = table.selectedRow
        if (row < 0) return
        val key = table.getValueAt(row, 0) as? String ?: return
        val fullKey = buildFullKey(key)
        val dialog = TranslationDialog(project, fullKey, Mode.EDIT)
        if (dialog.showAndGet()) {
            refresh()
        }
    }

    // ── Context menu ──────────────────────────────────────────────────────────

    private fun showContextMenu(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row < 0) return
        table.setRowSelectionInterval(row, row)

        val key = table.getValueAt(row, 0) as? String ?: return
        val usageCell = table.getValueAt(row, 1 + locales.size) as? String ?: return
        val isOrphan = usageCell.startsWith("0")

        val menu = JPopupMenu()
        if (isOrphan) {
            val deleteItem = javax.swing.JMenuItem("Delete orphan key")
            deleteItem.addActionListener { deleteOrphanKey(key) }
            menu.add(deleteItem)
        } else {
            val disabledItem = javax.swing.JMenuItem("No action available")
            disabledItem.isEnabled = false
            menu.add(disabledItem)
        }
        menu.show(e.component, e.x, e.y)
    }

    // ── Scan Orphans ──────────────────────────────────────────────────────────

    /**
     * Runs orphan detection in background. Updates allRows with usage counts,
     * then rebuilds the table on the EDT.
     */
    private fun scanOrphans() {
        scanButton.isEnabled = false
        val rowsToScan = allRows.toList()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scanning i18n orphan keys…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Counting usages…"
                val scanned = viewModel.countUsages(project, rowsToScan)

                ApplicationManager.getApplication().invokeLater {
                    // Merge scanned usage counts back into allRows, preserving original order
                    val usageByKey = scanned.associate { it.key to it.usageCount }
                    allRows = allRows.map { row ->
                        row.copy(usageCount = usageByKey[row.key] ?: row.usageCount)
                    }
                    applyFilters()
                    scanButton.isEnabled = true
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
     * Examples:
     *   "menu.home"        → FullKey(ns=null, compositeKey=[menu, home])
     *   "common:menu.home" → FullKey(ns=Literal(common), compositeKey=[menu, home])
     */
    private fun buildFullKey(keyString: String): FullKey {
        val colonIdx = keyString.indexOf(':')
        val (ns, keyPath) = if (colonIdx > 0) {
            Literal(keyString.substring(0, colonIdx)) to keyString.substring(colonIdx + 1)
        } else {
            null to keyString
        }
        return FullKey(source = keyString, ns = ns, compositeKey = keyPath.split('.').map { Literal(it) })
    }

    // ── Cell Renderers ────────────────────────────────────────────────────────

    /**
     * Cell renderer that highlights empty translation values.
     * Red background for completely missing values, orange for empty strings.
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

            // Values arrive verbatim from the translation files (newlines, indentation):
            // render a collapsed single line, keep the full raw value in the tooltip.
            if (column > 0 && column <= localeCount) {
                text = displayValue(raw)
                toolTipText = raw.ifBlank { null }
            } else {
                toolTipText = null
            }

            if (!isSelected && column > 0 && column <= localeCount) {
                background = when {
                    raw.isEmpty() -> JBColor(
                        java.awt.Color(255, 200, 200),
                        java.awt.Color(100, 40, 40)
                    )
                    raw.isBlank() -> JBColor(
                        java.awt.Color(255, 230, 180),
                        java.awt.Color(100, 80, 30)
                    )
                    else -> table.background
                }
            } else if (!isSelected) {
                background = table.background
            }

            return component
        }
    }

    /**
     * Cell renderer for the "Usage" column.
     * - Red foreground for 0 (orphan)
     * - Gray for -1 / "—" (not scanned)
     * - Normal otherwise
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
            val text = value?.toString() ?: ""
            toolTipText = if (text == "—") NOT_SCANNED_TOOLTIP else null
            if (!isSelected) {
                foreground = when {
                    text.startsWith("0") -> JBColor(
                        java.awt.Color(200, 0, 0),
                        java.awt.Color(220, 80, 80)
                    )
                    text == "—" -> JBColor.GRAY
                    else -> table.foreground
                }
                background = table.background
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

        WriteCommandAction.runWriteCommandAction(project, "Delete Orphan Key", null, {
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
