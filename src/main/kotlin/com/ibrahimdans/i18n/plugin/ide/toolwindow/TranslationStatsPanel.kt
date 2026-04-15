package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader.extractLocale
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.DecimalFormat
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

private val PERCENT_FORMAT = DecimalFormat("0.0")

/**
 * Panel displaying translation coverage statistics per locale.
 * Columns: Locale | Total | Translated | Missing | %
 * The % column is color-coded: green >= 90%, orange 50-90%, red < 50%.
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

        // Navigate to the translation file on click of the "Missing" column (index 3)
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                if (row < 0 || col != 3) return
                val rowStats = stats.getOrNull(row) ?: return
                if (rowStats.missing == 0) return
                navigateToMissingKey(rowStats)
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
            statusLabel.text = "${stats.size} locale(s) analyzed. Click a Missing count to navigate."
        }
    }

    /**
     * Navigates to the first translation source file for [rowStats]'s locale.
     * Prefers the source whose namespace matches the first missing key's prefix (e.g. "common:" → common.json).
     * Falls back to the first source for that locale.
     */
    private fun navigateToMissingKey(rowStats: LocaleStats) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val sourceService = project.getService(LocalizationSourceService::class.java)
            val allSources = sourceService.findAllSources(project)
            val localeSources = allSources.filter { extractLocale(it) == rowStats.locale }
            if (localeSources.isEmpty()) return@executeOnPooledThread

            // Pick the source whose namespace matches the first missing key (e.g. "common:" → common.json)
            val firstMissingKey = rowStats.missingKeys.firstOrNull()
            val ns = firstMissingKey?.substringBefore(":", missingDelimiterValue = "")?.takeIf { it.isNotEmpty() }
            val target = if (ns != null) {
                localeSources.firstOrNull { it.name.substringBeforeLast(".") == ns } ?: localeSources.first()
            } else {
                localeSources.first()
            }

            val virtualFile = runReadAction {
                target.tree?.value()?.containingFile?.virtualFile
            } ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                OpenFileDescriptor(project, virtualFile).navigate(true)
            }
        }
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
