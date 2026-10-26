package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader.extractLocale
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader.extractNamespace
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.awt.RelativePoint
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
import java.awt.Point
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

/** Bounds of the popup listing untranslated keys, unscaled pixels; rows beyond the max scroll. */
private const val POPUP_MAX_ROWS = 12
private const val POPUP_MIN_WIDTH = 320
private const val POPUP_MAX_WIDTH = 720
private const val POPUP_PADDING = 24
/** Room the popup's title bar keeps around its text: insets and the resize/move gestures. */
private const val POPUP_TITLE_PADDING = 48
/** Title bar plus hint line of the popup, added to the list's own height. */
private const val POPUP_CHROME_HEIGHT = 56

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

/**
 * The locale the missing keys are looked up in: the one [module] declares — or, with no module, the
 * lone configured one among [modules] ([ModuleSources.lone]), as everywhere else — when its files
 * hold it, otherwise the most translated one — a better guess than the project-wide settings for a
 * table of coverage.
 */
/**
 * Width of the untranslated-keys popup: wide enough for its widest line and for its title,
 * within [min] and [max]. Sized on the lines alone, a short list cut a long title such as
 * `common (default) — 'fr': 2 missing, 0 empty — reference: en` in the middle of a word.
 */
internal fun missingKeysPopupWidth(contentWidth: Int, titleWidth: Int, padding: Int, titlePadding: Int, min: Int, max: Int): Int =
    maxOf(contentWidth + padding, titleWidth + titlePadding).coerceIn(min, max)

internal fun selectReferenceLocale(
    stats: List<LocaleStats>,
    module: ModuleConfig? = null,
    modules: List<ModuleConfig> = emptyList(),
): String? =
    ReferenceLocale.declared(module ?: ModuleSources.lone(modules), stats.map { it.locale })
        ?: stats.maxByOrNull { it.translated }?.locale

/** What a locale cell says about its reviews: the keys to review there, and how many of them are stale. */
internal data class ReviewCount(val toReview: Int, val sourceChanged: Int)

/**
 * The [ReviewCount] of every cell of [report], by row group (null for the total row) then locale;
 * empty where [reviews] are off, so a module without a reviews folder shows nothing new.
 *
 * A key counts as the table's *To review* and *Source changed* filters keep it with only that
 * locale shown — the filters themselves, so the two tabs cannot disagree on what is left to review.
 * Like them, it counts keys, each plural form its own: a review is kept per form.
 */
internal fun reviewCounts(
    report: CoverageReport,
    reviews: TableReviews,
    viewModel: TableViewModel = TableViewModel(),
): Map<NamespaceFilter?, Map<String, ReviewCount>> {
    if (!reviews.enabled) return emptyMap()
    val rows = report.translations.map { (key, values) -> TranslationRow(key, values) }
    return report.rows.associate { row ->
        val inRow = row.group?.let { viewModel.filterByNamespace(it, rows) } ?: rows
        row.group to report.locales.associateWith { locale ->
            ReviewCount(
                toReview = viewModel.filterByStatus(StatusFilter.TO_REVIEW, inRow, listOf(locale), reviews).size,
                sourceChanged = viewModel.filterByStatus(StatusFilter.SOURCE_CHANGED, inRow, listOf(locale), reviews).size,
            )
        }
    }
}

/** The tooltip line [count] reads as: the keys to review, and the stale ones among them when there are. */
internal fun reviewTooltip(count: ReviewCount): String =
    if (count.sourceChanged > 0) PluginBundle.message("toolwindow.stats.cell.tooltip.review.stale", count.toReview, count.sourceChanged)
    else PluginBundle.message("toolwindow.stats.cell.tooltip.review", count.toReview)

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
 * Where the module has reviews (a `.i18n-reviews/` folder), a locale cell's tooltip also counts
 * its keys to review, and the stale ones among them — see [reviewCounts].
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
            val row = rowAtPoint(e.point)
            val cell = cellAt(row, columnAtPoint(e.point)) ?: return null
            val coverage = if (cell.untranslated > 0) PluginBundle.message("toolwindow.stats.cell.tooltip.missing", cell.translated, cell.total, cell.missing, cell.empty)
            else PluginBundle.message("toolwindow.stats.cell.tooltip.complete", cell.total)
            val review = report?.rows?.getOrNull(row)?.let { reviewCounts[it.group]?.get(cell.locale) } ?: return coverage
            return "<html>$coverage<br>${reviewTooltip(review)}</html>"
        }
    }
    private val statusLabel = JBLabel(PluginBundle.message("toolwindow.stats.loading"))
    private var report: CoverageReport? = null

    /** What [reviewCounts] read for [report]; empty where the module has no reviews. */
    private var reviewCounts: Map<NamespaceFilter?, Map<String, ReviewCount>> = emptyMap()

    /** The configuration the report was computed under; what the default group is called depends on it. */
    private var config: Config = Config()

    /** The sources the report was read from; whether the default group has a namespace to name depends on them. */
    private var sources: List<LocalizationSource> = emptyList()
    private var loadRequested = false

    /** Loads started by [refresh] and not finished yet; the table paints busy while any is. */
    private var loading: Int = 0

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
                val column = table.columnAtPoint(e.point)
                val cell = cellAt(row, column) ?: return
                if (cell.untranslated == 0) return
                val rect = table.getCellRect(row, column, true)
                showMissingKeysPopup(rowLabel(row), cell, RelativePoint(table, Point(rect.x, rect.y + rect.height)))
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
        setLoading(+1)
        ApplicationManager.getApplication().executeOnPooledThread {
            // A failed or cancelled load must not leave the table painting busy for good.
            try {
                val report = TranslationStatsAnalyzer.report(project, moduleConfig)
                val config = Settings.getInstance(project).config()
                val sources = TranslationDataLoader.findSources(project, moduleConfig)
                // Read as the table reads them, against the locales of this report.
                val viewModel = TableViewModel()
                val reviews = viewModel.loadReviews(project, ReviewLocation.rootOf(project, moduleConfig), report.locales, config, moduleConfig)
                val counts = reviewCounts(report, reviews, viewModel)
                ApplicationManager.getApplication().invokeLater {
                    this.config = config
                    this.sources = sources
                    this.reviewCounts = counts
                    rebuildTable(report)
                }
            } finally {
                ApplicationManager.getApplication().invokeLater { setLoading(-1) }
            }
        }
    }

    /** Counted, as in [TableViewPanel]: a second load may start before the first ends. Runs on the EDT. */
    private fun setLoading(delta: Int) {
        loading += delta
        table.setPaintBusy(loading > 0)
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
        row.group?.label(config, sources) ?: PluginBundle.message("toolwindow.stats.row.total")

    /** One line of the popup: a key the locale lacks ([LocaleState.MISSING]) or leaves blank ([LocaleState.EMPTY]). */
    private data class UntranslatedKey(val key: String, val state: LocaleState)

    /**
     * Shows, under the clicked cell, the untranslated keys of [cell] — one namespace in one
     * locale, or the whole locale from the total row — the missing ones first, then the empty
     * ones, each wearing the tree's mark for its state and what the key says in the reference
     * locale (the one with the highest coverage), which is what the translator needs to read.
     *
     * Enter or a click opens the translation dialog on the key, the one way to *fix* what
     * the popup lists; F4 opens the reference file at the key instead. The popup used to open
     * far from the cell, at a fixed 480×320 for two lines, and its only gesture led to the
     * reference file — reading the gap rather than closing it.
     */
    private fun showMissingKeysPopup(rowLabel: String, cell: LocaleStats, at: RelativePoint) {
        val referenceLocale = selectReferenceLocale(report?.total?.byLocale.orEmpty(), moduleConfig, config.modules) ?: return
        val translations = report?.translations.orEmpty()

        val listModel = DefaultListModel<UntranslatedKey>()
        cell.missingKeys.forEach { listModel.addElement(UntranslatedKey(it, LocaleState.MISSING)) }
        cell.emptyKeys.forEach { listModel.addElement(UntranslatedKey(it, LocaleState.EMPTY)) }
        val list = JBList(listModel)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = listModel.size.coerceIn(1, POPUP_MAX_ROWS)
        list.cellRenderer = object : ColoredListCellRenderer<UntranslatedKey>() {
            override fun customizeCellRenderer(list: JList<out UntranslatedKey>, value: UntranslatedKey, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = if (value.state == LocaleState.EMPTY) ICON_EMPTY else ICON_MISSING
                append(value.key)
                val reference = translations[value.key]?.get(referenceLocale)
                if (!reference.isNullOrBlank()) {
                    append("  ")
                    append(displayValue(reference), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                append("  ")
                append(
                    PluginBundle.message(if (value.state == LocaleState.EMPTY) "toolwindow.tree.status.empty" else "toolwindow.tree.status.missing"),
                    SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
                )
            }
        }
        ListSpeedSearch.installOn(list) { it.key }

        val title = PluginBundle.message(
            "toolwindow.stats.missing.popup.title",
            rowLabel, cell.locale, cell.missing, cell.empty, referenceLocale
        )
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JScrollPane(list), list)
            .setTitle(title)
            .setAdText(PluginBundle.message("toolwindow.stats.missing.popup.hint", referenceLocale))
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()

        val translate = {
            list.selectedValue?.let { chosen ->
                popup.cancel()
                editTranslation(chosen.key)
            }
        }
        val reference = {
            list.selectedValue?.let { chosen ->
                popup.cancel()
                navigateToKeyInReferenceFile(chosen.key, referenceLocale)
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && list.locationToIndex(e.point) >= 0) translate()
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> translate()
                    KeyEvent.VK_F4 -> reference()
                }
            }
        })
        list.selectedIndex = 0

        // Under the cell, sized to its lines: the popup is an annotation of the cell, not a window.
        // The title is measured in the bold label font the popup header uses, so it is not cut.
        val preferred = list.preferredScrollableViewportSize
        val titleWidth = list.getFontMetrics(JBUI.Fonts.label().asBold()).stringWidth(title)
        popup.size = Dimension(
            missingKeysPopupWidth(
                preferred.width, titleWidth,
                JBUI.scale(POPUP_PADDING), JBUI.scale(POPUP_TITLE_PADDING),
                JBUI.scale(POPUP_MIN_WIDTH), JBUI.scale(POPUP_MAX_WIDTH)
            ),
            preferred.height + JBUI.scale(POPUP_CHROME_HEIGHT)
        )
        popup.show(at)
    }

    /** One line of [value], so a multi-line reference does not stretch the popup. */
    private fun displayValue(value: String): String =
        value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /** Opens the translation dialog on [key] and reloads the tab once a value is written. */
    private fun editTranslation(key: String) {
        val fullKey = KeysSynchronizer().buildFullKey(key, Settings.getInstance(project).config())
        if (TranslationDialog(project, fullKey, Mode.EDIT, tabModule = moduleConfig).showAndGet()) refresh()
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
