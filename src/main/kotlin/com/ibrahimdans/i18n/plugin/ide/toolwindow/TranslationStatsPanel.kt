package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
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
import java.awt.Font
import java.awt.Graphics
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
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

internal fun parseTranslationKey(fullKey: String, config: Config = Config()): Pair<String?, List<String>> =
    KeySpelling.namespaceOf(fullKey) to KeySpelling.segmentsOf(fullKey, config)

internal fun selectReferenceLocale(stats: List<LocaleStats>): String? =
    stats.maxByOrNull { it.translated }?.locale

/** Columns before the locales: the row's namespace, then how many keys it holds. */
private const val LEADING_COLUMNS = 2

/**
 * Panel displaying translation coverage statistics, one row per namespace under a total row
 * and one column per locale — see [CoverageReport].
 * Columns: Namespace | Keys | one % per locale
 * Every locale cell carries a coverage bar tinted per tier (see [COMPLETE_THRESHOLD] /
 * [PARTIAL_THRESHOLD]); the legend spells the tiers out and the percentage stays
 * printed as text, so nothing depends on telling the hues apart.
 *
 * Clicking a locale cell with missing keys opens a popup listing those keys — the ones of
 * that namespace in that locale, so the list stays short. Each key navigates to its
 * position in the reference locale file.
 *
 * When [moduleConfig] is non-null, only translations from that module are analyzed.
 */
class TranslationStatsPanel(private val project: Project, private val moduleConfig: ModuleConfig? = null) : JPanel(BorderLayout()) {

    private val tableModel = object : DefaultTableModel() {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 1) Integer::class.java else String::class.java
    }
    private val table = object : JBTable(tableModel) {
        override fun getToolTipText(e: MouseEvent): String? {
            val cell = cellAt(rowAtPoint(e.point), columnAtPoint(e.point)) ?: return null
            return if (cell.missing > 0) PluginBundle.message("toolwindow.stats.cell.tooltip.missing", cell.translated, cell.total, cell.missing)
            else PluginBundle.message("toolwindow.stats.cell.tooltip.complete", cell.total)
        }
    }
    private val statusLabel = JBLabel(PluginBundle.message("toolwindow.stats.loading"))
    private var report: CoverageReport? = null
    private var loadRequested = false

    init {
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        installRenderer()

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val cell = cellAt(row, table.columnAtPoint(e.point)) ?: return
                if (cell.missing == 0) return
                showMissingKeysPopup(rowLabel(row), cell)
            }
        })
        // Hand cursor over drillable cells, so the click affordance is visible.
        table.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val cell = cellAt(table.rowAtPoint(e.point), table.columnAtPoint(e.point))
                table.cursor = if (cell != null && cell.missing > 0)
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
            val report = TranslationStatsAnalyzer.report(project, moduleConfig)
            ApplicationManager.getApplication().invokeLater {
                rebuildTable(report)
            }
        }
    }

    private fun rebuildTable(newReport: CoverageReport) {
        report = newReport
        val columns: Array<Any?> = (listOf(
            PluginBundle.message("toolwindow.stats.column.namespace"),
            PluginBundle.message("toolwindow.stats.column.keys"),
        ) + newReport.locales).toTypedArray()
        val rows: Array<Array<Any?>> = newReport.rows.map { row ->
            val cells: List<Any?> = listOf(rowLabel(row), row.total) + newReport.locales.map { locale ->
                row.of(locale)?.let { PERCENT_FORMAT.format(it.percent) + "%" }
            }
            cells.toTypedArray()
        }.toTypedArray()
        tableModel.setDataVector(rows, columns)

        if (newReport.locales.isEmpty()) {
            statusLabel.text = PluginBundle.message("toolwindow.stats.empty")
        } else {
            statusLabel.text = PluginBundle.message("toolwindow.stats.status", newReport.locales.size)
        }
    }

    /** One renderer for every cell: the key count is a number, and must read in bold on the total row too. */
    private fun installRenderer() {
        val renderer = PercentCellRenderer()
        table.setDefaultRenderer(String::class.java, renderer)
        table.setDefaultRenderer(Integer::class.java, renderer)
    }

    /** The stats behind the cell at ([row], [column]), or null off a locale cell. */
    private fun cellAt(row: Int, column: Int): LocaleStats? {
        val report = report ?: return null
        val locale = report.locales.getOrNull(column - LEADING_COLUMNS) ?: return null
        return report.rows.getOrNull(row)?.of(locale)
    }

    private fun rowLabel(row: Int): String = report?.rows?.getOrNull(row)?.let { rowLabel(it) }.orEmpty()

    private fun rowLabel(row: NamespaceStats): String =
        row.group?.label ?: PluginBundle.message("toolwindow.stats.row.total")

    /**
     * Shows a popup listing the missing keys of [cell] — one namespace in one locale, or the
     * whole locale from the total row. Clicking a key, or pressing Enter on it, navigates to
     * it in the reference locale file (the locale with the highest translation coverage).
     */
    private fun showMissingKeysPopup(rowLabel: String, cell: LocaleStats) {
        val referenceLocale = selectReferenceLocale(report?.total?.byLocale.orEmpty()) ?: return

        val listModel = DefaultListModel<String>()
        cell.missingKeys.forEach { listModel.addElement(it) }
        val list = JBList(listModel)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION

        val navigate = { list.selectedValue?.let { navigateToKeyInReferenceFile(it, referenceLocale) } }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                navigate()
            }
        })
        // The popup takes the focus, so the keyboard must lead somewhere too.
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) navigate()
            }
        })

        val scrollPane = JScrollPane(list)
        scrollPane.preferredSize = Dimension(480, 320)

        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, list)
            .setTitle(
                PluginBundle.message(
                    "toolwindow.stats.missing.popup.title",
                    rowLabel, cell.locale, cell.missing, referenceLocale
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

            val (ns, segments) = parseTranslationKey(fullKey, Settings.getInstance(project).config())
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
     * Cell renderer for the locale columns: draws a thin coverage bar under the percentage,
     * its width the coverage ratio and its tint the tier. The text is painted on the
     * plain cell background, never over the bar, so the figure stays readable whatever
     * the theme does with the tint. The namespace column uses default rendering, the total
     * row in bold.
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
            font = table.font
            if (column >= LEADING_COLUMNS) {
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
                horizontalAlignment = if (column == 1) RIGHT else LEFT
                if (report?.rows?.getOrNull(row)?.let { it.group == null } == true) font = table.font.deriveFont(Font.BOLD)
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
