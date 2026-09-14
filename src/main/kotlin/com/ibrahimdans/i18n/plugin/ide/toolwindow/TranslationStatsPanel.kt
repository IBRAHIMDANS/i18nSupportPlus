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
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
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
import javax.swing.JList
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

/** Unscaled width, in pixels, of the area at the right of a locale cell where the percentage sits. */
private const val PERCENT_AREA_WIDTH = 60

/** Unscaled inset of a locale cell's contents; the bar and the count start there. */
private const val CELL_INSET = 4

/** Unscaled preferred widths of the Namespace and Keys columns; the locales share the rest. */
private const val NAMESPACE_COLUMN_WIDTH = 180
private const val KEYS_COLUMN_WIDTH = 60

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
private val SECONDARY_TEXT by lazy { JBColor.namedColor("Label.infoForeground", JBColor.GRAY) }

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
            return if (cell.untranslated > 0) PluginBundle.message("toolwindow.stats.cell.tooltip.missing", cell.translated, cell.total, cell.missing, cell.empty)
            else PluginBundle.message("toolwindow.stats.cell.tooltip.complete", cell.total)
        }
    }
    private val statusLabel = JBLabel(PluginBundle.message("toolwindow.stats.loading"))
    private var report: CoverageReport? = null
    private var loadRequested = false

    init {
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        // Nothing is selected in a read-only table whose click opens a popup: a selection
        // band would only paint over the bar's tint.
        table.rowSelectionAllowed = false
        table.columnSelectionAllowed = false
        table.isFocusable = false
        installRenderer()

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val cell = cellAt(row, table.columnAtPoint(e.point)) ?: return
                if (cell.untranslated == 0) return
                showMissingKeysPopup(rowLabel(row), cell)
            }
        })
        // Hand cursor over drillable cells, so the click affordance is visible.
        table.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val cell = cellAt(table.rowAtPoint(e.point), table.columnAtPoint(e.point))
                table.cursor = if (cell != null && cell.untranslated > 0)
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
        // Locales are spelled the way the tree's badges spell them.
        val columns: Array<Any?> = (listOf(
            PluginBundle.message("toolwindow.stats.column.namespace"),
            PluginBundle.message("toolwindow.stats.column.keys"),
        ) + newReport.locales.map { it.uppercase() }).toTypedArray()
        val rows: Array<Array<Any?>> = newReport.rows.map { row ->
            val cells: List<Any?> = listOf(rowLabel(row), row.total) + newReport.locales.map { locale ->
                row.of(locale)?.let { PERCENT_FORMAT.format(it.percent) + "%" }
            }
            cells.toTypedArray()
        }.toTypedArray()
        tableModel.setDataVector(rows, columns)
        sizeColumns()

        if (newReport.locales.isEmpty()) {
            statusLabel.text = PluginBundle.message("toolwindow.stats.empty")
        } else {
            statusLabel.text = PluginBundle.message("toolwindow.stats.status", newReport.locales.size)
        }
    }

    /**
     * A namespace is a word and a key count two digits: neither deserves the equal share of
     * the viewport `AUTO_RESIZE_ALL_COLUMNS` hands out, which put the count half a screen
     * away from its label. The two keep a fixed width; the locale columns take the rest.
     */
    private fun sizeColumns() {
        if (table.columnCount < LEADING_COLUMNS) return
        table.columnModel.getColumn(0).apply {
            preferredWidth = JBUI.scale(NAMESPACE_COLUMN_WIDTH)
            maxWidth = JBUI.scale(NAMESPACE_COLUMN_WIDTH * 2)
        }
        table.columnModel.getColumn(1).apply {
            preferredWidth = JBUI.scale(KEYS_COLUMN_WIDTH)
            maxWidth = JBUI.scale(KEYS_COLUMN_WIDTH)
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

    /** One line of the popup: a key the locale lacks ([LocaleState.MISSING]) or leaves blank ([LocaleState.EMPTY]). */
    private data class UntranslatedKey(val key: String, val state: LocaleState)

    /**
     * Shows a popup listing the untranslated keys of [cell] — one namespace in one locale, or
     * the whole locale from the total row — the missing ones first, then the empty ones, each
     * wearing the tree's mark for its state. Clicking a key, or pressing Enter on it,
     * navigates to it in the reference locale file (the locale with the highest coverage).
     */
    private fun showMissingKeysPopup(rowLabel: String, cell: LocaleStats) {
        val referenceLocale = selectReferenceLocale(report?.total?.byLocale.orEmpty()) ?: return

        val listModel = DefaultListModel<UntranslatedKey>()
        cell.missingKeys.forEach { listModel.addElement(UntranslatedKey(it, LocaleState.MISSING)) }
        cell.emptyKeys.forEach { listModel.addElement(UntranslatedKey(it, LocaleState.EMPTY)) }
        val list = JBList(listModel)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ColoredListCellRenderer<UntranslatedKey>() {
            override fun customizeCellRenderer(list: JList<out UntranslatedKey>, value: UntranslatedKey, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = if (value.state == LocaleState.EMPTY) ICON_EMPTY else ICON_MISSING
                append(value.key)
                append("  ")
                append(
                    PluginBundle.message(if (value.state == LocaleState.EMPTY) "toolwindow.tree.status.empty" else "toolwindow.tree.status.missing"),
                    SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
                )
            }
        }

        val navigate = { list.selectedValue?.let { navigateToKeyInReferenceFile(it.key, referenceLocale) } }
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
                    rowLabel, cell.locale, cell.missing, cell.empty, referenceLocale
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
     * Cell renderer for the locale columns, laid out as `11/13 [=====    ] 84.6%`: the
     * translated count over the key count in secondary text on the left, the coverage bar
     * beside it — its width the ratio, its tint the tier — and the percentage in a fixed
     * area on the right. The bar used to run under the whole cell with the percentage at
     * its far end, so `84.6%` sat over the *empty* part of the track; and the counts, the
     * one thing the old per-locale table showed that this one did not, were only in the
     * tooltip. The namespace and key columns use default rendering, the total row in bold.
     */
    private inner class PercentCellRenderer : DefaultTableCellRenderer() {
        private var cell: LocaleStats? = null
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
            cell = null
            font = table.font
            val bold = report?.rows?.getOrNull(row)?.let { it.group == null } == true
            if (bold) font = table.font.deriveFont(Font.BOLD)
            if (column >= LEADING_COLUMNS) {
                cell = cellAt(row, column)
                val pct = cell?.percent ?: 0.0
                barColor = when {
                    pct >= COMPLETE_THRESHOLD -> COVERAGE_COMPLETE
                    pct >= PARTIAL_THRESHOLD  -> COVERAGE_PARTIAL
                    else                      -> COVERAGE_LOW
                }
                horizontalAlignment = RIGHT
                border = JBUI.Borders.empty(0, CELL_INSET)
                isOpaque = false
                cellBackground = if (isSelected) table.selectionBackground else table.background
            } else {
                isOpaque = true
                if (!isSelected) background = table.background
                horizontalAlignment = if (column == 1) RIGHT else LEFT
            }
            return component
        }

        override fun paintComponent(g: Graphics) {
            val stats = cell
            if (stats == null) {
                super.paintComponent(g)
                return
            }
            g.color = cellBackground
            g.fillRect(0, 0, width, height)
            // The percentage, right-aligned by the label itself.
            super.paintComponent(g)

            val left = JBUI.scale(CELL_INSET)
            val barRight = width - JBUI.scale(PERCENT_AREA_WIDTH)
            val barWidth = (barRight - left).coerceAtLeast(0)

            val count = "${stats.translated}/${stats.total}"
            val metrics = g.getFontMetrics(font)
            g.color = SECONDARY_TEXT
            g.font = font
            g.drawString(count, left, (height + metrics.ascent - metrics.descent) / 2)

            val top = height - BAR_HEIGHT - 1
            g.color = COVERAGE_TRACK
            g.fillRect(left, top, barWidth, BAR_HEIGHT)
            g.color = barColor
            g.fillRect(left, top, (barWidth * stats.percent / 100.0).toInt().coerceIn(0, barWidth), BAR_HEIGHT)
        }
    }
}
