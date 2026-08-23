package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader.extractLocale
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader.extractNamespace
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.DecimalFormat
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

private val PERCENT_FORMAT = DecimalFormat("0.0")

/** Coverage tiers, in percent, shared by the % column renderer and the legend. */
private const val COMPLETE_THRESHOLD = 90
private const val PARTIAL_THRESHOLD = 50

/** Height, in pixels, of the coverage bar drawn under the percentage. */
private const val BAR_HEIGHT = 3

// Colors come from the IDE scheme rather than hand-picked RGB values, so the bar follows
// the active theme (and any custom one). The RGB arguments are only fallbacks, taken from
// the default light/dark themes. No `ProgressBar.*` key carries a warning tint, hence
// `Component.warningFocusColor` for the middle tier.
// Built lazily: the file also holds plain functions covered by a non-platform unit test,
// and initializing Swing colors just to call one of them would be a needless dependency.
private val COVERAGE_COMPLETE by lazy { JBColor.namedColor("ProgressBar.passedColor", 0x55A76A, 0x57965C) }
private val COVERAGE_PARTIAL by lazy { JBColor.namedColor("Component.warningFocusColor", 0xFFAF0F, 0x9E814A) }
private val COVERAGE_LOW by lazy { JBColor.namedColor("ProgressBar.failedColor", 0xE55765, 0xBD5757) }
private val COVERAGE_TRACK by lazy { JBColor.namedColor("ProgressBar.trackColor", 0xDFE1E5, 0x43454A) }

internal fun parseTranslationKey(fullKey: String): Pair<String?, List<String>> {
    val ns = if (fullKey.contains(":")) fullKey.substringBefore(":") else null
    val keyPath = if (ns != null) fullKey.substringAfter(":") else fullKey
    return ns to keyPath.split(".")
}

internal fun selectReferenceLocale(stats: List<LocaleStats>): String? =
    stats.maxByOrNull { it.translated }?.locale

/**
 * Panel displaying translation coverage statistics per locale.
 * Columns: Locale | Total | Translated | Missing | %
 * The % column carries a coverage bar tinted per tier (see [COMPLETE_THRESHOLD] /
 * [PARTIAL_THRESHOLD]); the legend spells the tiers out and the percentage stays
 * printed as text, so nothing depends on telling the hues apart.
 *
 * Clicking any cell on a row with missing keys opens a popup listing
 * those keys. Each key navigates to its position in the reference locale file.
 *
 * When [moduleConfig] is non-null, only translations from that module are analyzed.
 */
class TranslationStatsPanel(private val project: Project, private val moduleConfig: ModuleConfig? = null) : JPanel(BorderLayout()) {

    private val tableModel = object : DefaultTableModel(
        arrayOf(
            PluginBundle.message("toolwindow.stats.column.locale"),
            PluginBundle.message("toolwindow.stats.column.total"),
            PluginBundle.message("toolwindow.stats.column.translated"),
            PluginBundle.message("toolwindow.stats.column.missing"),
            PluginBundle.message("toolwindow.stats.column.percent"),
        ),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            1, 2, 3 -> Integer::class.java
            else -> String::class.java
        }
    }
    private val table = object : JBTable(tableModel) {
        override fun getToolTipText(e: MouseEvent): String? {
            val row = rowAtPoint(e.point)
            val rowStats = stats.getOrNull(row) ?: return null
            return if (rowStats.missing > 0) PluginBundle.message("toolwindow.stats.missing.tooltip", rowStats.missing) else null
        }
    }
    private val statusLabel = JBLabel(PluginBundle.message("toolwindow.stats.loading"))
    private var stats: List<LocaleStats> = emptyList()
    private var loadRequested = false

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
        // Hand cursor over drillable rows, so the click affordance is visible.
        table.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val rowStats = stats.getOrNull(table.rowAtPoint(e.point))
                table.cursor = if (rowStats != null && rowStats.missing > 0)
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                else
                    Cursor.getDefaultCursor()
            }
        })

        val legend = JBLabel(PluginBundle.message("toolwindow.stats.legend", COMPLETE_THRESHOLD, PARTIAL_THRESHOLD))
        legend.border = JBUI.Borders.empty(2, 4)
        add(legend, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    /**
     * Loads the statistics the first time the panel is shown. Refreshing is the tool
     * window toolbar's job, so the panel would otherwise sit empty until the user
     * clicked it. Swing calls this on the EDT, which is what [refresh] expects; the
     * flag keeps the container's own initial [refresh] from being doubled.
     */
    override fun addNotify() {
        super.addNotify()
        if (!loadRequested) refresh()
    }

    fun refresh() {
        loadRequested = true
        statusLabel.text = PluginBundle.message("toolwindow.stats.loading")
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
            statusLabel.text = PluginBundle.message("toolwindow.stats.empty")
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
        val referenceLocale = selectReferenceLocale(stats) ?: return

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
            .setTitle(
                PluginBundle.message(
                    "toolwindow.stats.missing.popup.title",
                    rowStats.locale, rowStats.missing, referenceLocale
                )
            )
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

            val (ns, segments) = parseTranslationKey(fullKey)
            val refSources = allSources.filter { extractLocale(it) == referenceLocale }
            val target = if (ns != null) {
                refSources.firstOrNull { extractNamespace(it) == ns } ?: refSources.firstOrNull()
            } else {
                refSources.firstOrNull()
            } ?: return@executeOnPooledThread

            val result = ReadAction.compute<Pair<VirtualFile, Int>?, RuntimeException> {
                val tree = target.tree ?: return@compute null
                var node: Tree<PsiElement> = tree
                for (segment in segments) {
                    node = node.findChild(segment) ?: return@compute fallbackFile(tree)
                }
                val psi: PsiElement = node.value()
                val vf = psi.containingFile?.virtualFile ?: return@compute null
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
     * Cell renderer for the % column: draws a thin coverage bar under the percentage,
     * its width the coverage ratio and its tint the tier. The text is painted on the
     * plain cell background, never over the bar, so the figure stays readable whatever
     * the theme does with the tint. Other columns use default rendering.
     */
    private inner class PercentCellRenderer : DefaultTableCellRenderer() {
        private var barFraction = -1.0
        private var barColor: Color? = null
        private var cellBackground: Color? = null

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            barFraction = -1.0
            if (column == 4) {
                val pct = value?.toString()?.removeSuffix("%")?.toDoubleOrNull() ?: 0.0
                barFraction = (pct / 100.0).coerceIn(0.0, 1.0)
                barColor = when {
                    pct >= COMPLETE_THRESHOLD -> COVERAGE_COMPLETE
                    pct >= PARTIAL_THRESHOLD  -> COVERAGE_PARTIAL
                    else                      -> COVERAGE_LOW
                }
                isOpaque = false
                cellBackground = if (isSelected) table.selectionBackground else table.background
            } else {
                isOpaque = true
                if (!isSelected) background = table.background
            }
            return component
        }

        override fun paintComponent(g: Graphics) {
            if (barFraction < 0) {
                super.paintComponent(g)
                return
            }
            g.color = cellBackground
            g.fillRect(0, 0, width, height)
            super.paintComponent(g)
            val top = height - BAR_HEIGHT - 1
            g.color = COVERAGE_TRACK
            g.fillRect(0, top, width, BAR_HEIGHT)
            g.color = barColor
            g.fillRect(0, top, (width * barFraction).toInt(), BAR_HEIGHT)
        }
    }
}
