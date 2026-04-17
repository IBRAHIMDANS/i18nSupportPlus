package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader.extractLocale
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader.extractNamespace
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.DecimalFormat
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

private val PERCENT_FORMAT = DecimalFormat("0.0")

/**
 * Panel displaying translation coverage statistics per locale.
 * Columns: Locale | Total | Translated | Missing | %
 * The % column is color-coded: green >= 90%, orange 50-90%, red < 50%.
 *
 * Clicking any cell on a row with missing keys opens a popup listing
 * those keys. Each key navigates to its position in the reference locale file.
 *
 * When [moduleConfig] is non-null, only translations from that module are analyzed.
 */
class TranslationStatsPanel(private val project: Project, private val moduleConfig: ModuleConfig? = null) : JPanel(BorderLayout()) {

    private val tableModel = object : DefaultTableModel(
        arrayOf("Locale", "Total", "Translated", "Missing", "%"),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            1, 2, 3 -> Integer::class.java
            else -> String::class.java
        }
    }
    private val table = JBTable(tableModel)
    private val statusLabel = JBLabel("Click Refresh to load stats")
    private var stats: List<LocaleStats> = emptyList()

    init {
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        table.setDefaultRenderer(String::class.java, PercentCellRenderer())

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                if (row < 0) return
                val rowStats = stats.getOrNull(row) ?: return
                if (rowStats.missing == 0) return
                showMissingKeysPopup(rowStats)
            }
        })

        val toolbar = buildToolbar()
        add(toolbar, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    private fun buildToolbar(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        val refreshButton = JButton("Refresh", AllIcons.Actions.Refresh)
        refreshButton.addActionListener { refresh() }
        panel.add(refreshButton)
        return panel
    }

    /**
     * Loads translation stats on a background thread and populates the table on the EDT.
     */
    fun refresh() {
        statusLabel.text = "Loading..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val stats = TranslationStatsAnalyzer.analyze(project, moduleConfig)
            ApplicationManager.getApplication().invokeLater {
                rebuildTable(stats)
            }
        }
    }

    private fun rebuildTable(newStats: List<LocaleStats>) {
        stats = newStats
        tableModel.rowCount = 0
        for (s in stats) {
            tableModel.addRow(
                arrayOf<Any?>(
                    s.locale,
                    s.total,
                    s.translated,
                    s.missing,
                    PERCENT_FORMAT.format(s.percent) + "%"
                )
            )
        }
        if (stats.isEmpty()) {
            statusLabel.text = "No translation data found."
        } else {
            statusLabel.text = "${stats.size} locale(s) analyzed. Click a row to see missing keys."
        }
    }

    /**
     * Shows a popup listing all missing keys for [rowStats]'s locale.
     * Clicking a key navigates to it in the reference locale file (the locale
     * with the highest translation coverage).
     */
    private fun showMissingKeysPopup(rowStats: LocaleStats) {
        val referenceLocale = stats.maxByOrNull { it.translated }?.locale ?: return

        val listModel = DefaultListModel<String>()
        rowStats.missingKeys.forEach { listModel.addElement(it) }
        val list = JBList(listModel)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val key = list.selectedValue ?: return
                navigateToKeyInReferenceFile(key, referenceLocale)
            }
        })

        val scrollPane = JScrollPane(list)
        scrollPane.preferredSize = Dimension(480, 320)

        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, list)
            .setTitle("Missing in '${rowStats.locale}' (${rowStats.missing}) — reference: $referenceLocale")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
            .showInBestPositionFor(DataManager.getInstance().getDataContext(table))
    }

    /**
     * Navigates to [fullKey] in the file of [referenceLocale].
     * Parses "namespace:path.to.key" or "path.to.key", traverses the PSI tree
     * to find the exact element offset, then opens the file at that position.
     * Falls back to opening the file at offset 0 if traversal fails.
     */
    private fun navigateToKeyInReferenceFile(fullKey: String, referenceLocale: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val service = project.getService(LocalizationSourceService::class.java)
            val allSources = service.findAllSources(project)

            val ns = if (fullKey.contains(":")) fullKey.substringBefore(":") else null
            val keyPath = if (ns != null) fullKey.substringAfter(":") else fullKey
            val segments = keyPath.split(".")

            val refSources = allSources.filter { extractLocale(it) == referenceLocale }
            val target = if (ns != null) {
                refSources.firstOrNull { extractNamespace(it) == ns } ?: refSources.firstOrNull()
            } else {
                refSources.firstOrNull()
            } ?: return@executeOnPooledThread

            val result = runReadAction {
                val tree = target.tree ?: return@runReadAction null
                var node = tree
                for (segment in segments) {
                    node = node.findChild(segment) ?: return@runReadAction fallbackFile(tree)
                }
                val psi: PsiElement = node.value()
                val vf = psi.containingFile?.virtualFile ?: return@runReadAction null
                vf to psi.textOffset
            } ?: return@executeOnPooledThread

            ApplicationManager.getApplication().invokeLater {
                OpenFileDescriptor(project, result.first, result.second).navigate(true)
            }
        }
    }

    private fun fallbackFile(tree: Tree<PsiElement>): Pair<VirtualFile, Int>? {
        val psi = tree.value()
        val vf = psi.containingFile?.virtualFile ?: return null
        return vf to 0
    }

    /**
     * Cell renderer for the % column: green >= 90%, orange 50-90%, red < 50%.
     * Other columns use default rendering.
     */
    private inner class PercentCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (!isSelected && column == 4) {
                val text = value?.toString()?.removeSuffix("%") ?: ""
                val pct = text.toDoubleOrNull() ?: 0.0
                background = when {
                    pct >= 90.0 -> JBColor(Color(200, 240, 200), Color(30, 90, 30))
                    pct >= 50.0 -> JBColor(Color(255, 230, 150), Color(100, 80, 20))
                    else        -> JBColor(Color(255, 200, 200), Color(100, 40, 40))
                }
            } else if (!isSelected) {
                background = table.background
            }
            return component
        }
    }
}
