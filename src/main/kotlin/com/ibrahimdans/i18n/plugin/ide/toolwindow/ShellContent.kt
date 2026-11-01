package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.project.Project
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

/** Which of the three shells the current project configuration deserves. */
internal enum class ShellLayout { EMPTY, SINGLE, MULTI }

/**
 * What the shell knows about the project before it draws anything: where it looked for
 * translations, and how many it found.
 *
 * Plain data on purpose — the layout decision and the empty-state text are then testable
 * without a Swing stack, which the panel itself is not.
 */
internal data class ShellDiagnostics(
    val searchedPaths: List<String>,
    val sourceCount: Int,
    val modules: List<ModuleConfig>,
    /** The modules put aside: listed greyed in the selector after [modules], to switch to them. */
    val asideModules: List<ModuleConfig> = emptyList()
) {

    /**
     * Nothing found is a case of its own. Without it an unconfigured project gets a tree
     * holding a single "Translations" node and an empty statistics table — neither of which
     * says that the plugin simply has nothing to read, and neither of which leads anywhere.
     */
    val layout: ShellLayout
        get() = when {
            sourceCount == 0 -> ShellLayout.EMPTY
            modules.size >= 2 -> ShellLayout.MULTI
            else -> ShellLayout.SINGLE
        }

    /** Module labels as the selector shows them, unnamed modules included. */
    val moduleNames: List<String>
        get() = modules.map(::labelOf)

    /** Labels of the [asideModules]. */
    val asideNames: List<String>
        get() = asideModules.map(::labelOf)

    /**
     * What the selector lists: the active modules when there are several, then the ones put aside —
     * shown as soon as there is one, or a module focused on could never be left.
     */
    val selectorItems: List<ModuleSelectorItem>
        get() {
            val active = if (layout == ShellLayout.MULTI || asideModules.isNotEmpty()) modules else emptyList()
            val items = active.map { ModuleSelectorItem(it, labelOf(it), aside = false) } +
                asideModules.map { ModuleSelectorItem(it, labelOf(it), aside = true) }
            return if (items.size >= 2) items else emptyList()
        }

    private fun labelOf(module: ModuleConfig): String = module.name.ifBlank { PluginBundle.message("toolwindow.module.unnamed") }

    companion object {

        /**
         * The roots the plugin scans, as the user configured them. An empty list means no root
         * was ever set and the scan covers the whole project — the empty state says so instead
         * of showing nothing.
         */
        fun searchedPaths(config: Config): List<String> =
            (config.modules.map { it.rootDirectory } + config.translationsRoot)
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotBlank() }
                .distinct()
    }
}

/**
 * True when [next] describes a different shell from [previous], so the content must be built
 * again.
 *
 * A plain refresh must not rebuild: it would drop the selected view, the selected module and
 * the scroll position every time a translation file is saved.
 */
internal fun shellNeedsRebuild(previous: ShellDiagnostics?, next: ShellDiagnostics): Boolean = when {
    previous == null -> true
    previous.layout != next.layout -> true
    previous.moduleNames != next.moduleNames -> true
    previous.asideNames != next.asideNames -> true
    // The empty state prints the diagnostics themselves, so it must follow any change to them.
    next.layout == ShellLayout.EMPTY -> previous != next
    else -> false
}

/** One entry of the module selector: a module on screen, or one put aside that choosing focuses on. */
internal data class ModuleSelectorItem(val module: ModuleConfig, val label: String, val aside: Boolean) {
    override fun toString(): String = label
}

/** The three panels bound to one module, or to the whole project when [config] is null. */
internal data class ModulePanelSet(
    val config: ModuleConfig?,
    val tree: TreeViewPanel,
    val table: TableViewPanel,
    val stats: TranslationStatsPanel
)

/**
 * Narrowest tool window, in unscaled pixels, whose top bar still holds the table's Namespace and
 * Status filters next to the search field. Below it — a window docked to a side — they keep their
 * own row above the table.
 */
internal const val INLINE_FILTERS_MIN_WIDTH = 1000

/**
 * The tool window's content area: either the empty state, or the tree, the table and the
 * statistics of the module currently selected in the toolbar, one at a time.
 *
 * Deliberately not a member of [I18nToolWindowPanel]: that class reaches for [ActionManager]
 * in its constructor, which the headless test container does not provide, so anything built
 * inside it is out of reach of a test. Built here, the layout is a plain Swing tree a test
 * can walk.
 *
 * [viewState], when given, restores the view selected last time and records every change of it.
 * It is optional so the layout can still be built — and tested — with no workspace behind it.
 *
 * [filterSlot] is the place the top bar keeps for the table's filters: while the table is the
 * view on screen and the window is wide enough (see [fitToWidth]), its filter bar moves there, and
 * the row it took above the table goes to the rows.
 */
internal class ShellContent(
    project: Project,
    val diagnostics: ShellDiagnostics,
    private val onRunWizard: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val viewState: ToolWindowViewState? = null,
    private val onClearSearch: () -> Unit = {},
    private val filterSlot: JPanel? = null
) {

    /**
     * One set of panels per module in multi-module mode; a single project-wide set otherwise.
     *
     * A lone configured module stays project-wide, as it has always been: scoping it to its
     * own root would silently hide whatever lives outside it, which is a change nobody asked
     * for and nobody would see coming.
     */
    private val moduleSets: List<ModulePanelSet> = when (diagnostics.layout) {
        ShellLayout.EMPTY -> emptyList()
        ShellLayout.SINGLE -> listOf(newPanelSet(project, null))
        ShellLayout.MULTI -> diagnostics.modules.map { newPanelSet(project, it) }
    }.onEach { it.table.onClearSearch = onClearSearch }

    private val cards = CardLayout()

    /**
     * The three views, one at a time; picked from the toolbar — see [selectedView].
     *
     * Two modules used to nest a tab pane per view inside a tab pane per module: in a
     * right-docked window two rows of tabs eat the useful height, and the inner selection was
     * lost from one module to the next. The module moved to a selector in the toolbar, and
     * switching it swaps the three components in place — so the chosen view survives it. The
     * views then left their own row of tabs for the toolbar too: docked at the bottom, that row
     * was one of four stacked above an eight-row table.
     */
    val views: JPanel? = moduleSets.firstOrNull()?.let { first ->
        JPanel(cards).apply { addViews(this, first) }
    }

    /**
     * The view on screen, restored from [viewState] and written back on every change.
     *
     * Saved in the project scope, not per module: the view is shared on purpose between modules —
     * [showModule] keeps it across a switch — so a per-module value would either fight that or
     * never be read. Restoring it writes nothing: only the setter does.
     */
    var selectedView: ToolWindowTab = viewState?.activeTab(null) ?: ToolWindowTab.TREE
        set(value) {
            field = value
            views?.let { cards.show(it, value.name) }
            viewState?.setActiveTab(null, value)
            placeFilters()
        }

    private var visibleModule = 0

    /** The tool window's width as last reported by [fitToWidth]; 0 until then. */
    private var availableWidth = 0

    /** What the tool window shows. */
    val component: JComponent = views ?: buildEmptyState()

    /** True when there are views to pick from, i.e. not the empty state. */
    val hasViews: Boolean get() = views != null

    init {
        views?.let { cards.show(it, selectedView.name) }
    }

    /** The module whose panels are on screen; null when the shell is not module-scoped. */
    val activeModule: ModuleConfig?
        get() = moduleSets.getOrNull(visibleModule)?.config

    /** Shows the panels of module [index] in place of the current ones, keeping the selected view. */
    fun showModule(index: Int) {
        val panel = views ?: return
        val set = moduleSets.getOrNull(index) ?: return
        if (index == visibleModule) return
        // The filter bar of the module going away may sit in the top bar: it goes home first.
        moduleSets[visibleModule].table.dockFilterBar()
        panel.removeAll()
        addViews(panel, set)
        visibleModule = index
        cards.show(panel, selectedView.name)
        panel.revalidate()
        panel.repaint()
        placeFilters()
    }

    /**
     * Records the tool window's [width] and decides where the table's filters go: into
     * [filterSlot], next to the search field, while the table is on screen and the width allows
     * it; above the table otherwise.
     */
    fun fitToWidth(width: Int) {
        availableWidth = width
        placeFilters()
    }

    /** Whether the visible table's filters sit in the top bar right now; for the tests. */
    internal val filtersInTopBar: Boolean
        get() = filterSlot != null && filterSlot.componentCount > 0

    private fun placeFilters() {
        val slot = filterSlot ?: return
        val visible = moduleSets.getOrNull(visibleModule)?.table
        val inline = visible != null && selectedView == ToolWindowTab.TABLE &&
            availableWidth >= JBUI.scale(INLINE_FILTERS_MIN_WIDTH)
        for (set in moduleSets) {
            if (!inline || set.table !== visible) set.table.dockFilterBar()
        }
        slot.removeAll()
        // Adding the bar to the slot takes it out of the table by itself: a component has one parent.
        if (inline) slot.add(visible!!.filterBar, BorderLayout.CENTER)
        slot.isVisible = inline
        slot.revalidate()
        slot.repaint()
    }

    private fun addViews(panel: JPanel, set: ModulePanelSet) {
        panel.add(set.tree, ToolWindowTab.TREE.name)
        panel.add(set.table, ToolWindowTab.TABLE.name)
        panel.add(set.stats, ToolWindowTab.STATS.name)
    }

    /** Filters every module, not only the visible one, so the query survives a module switch. */
    fun applyFilter(query: String) {
        for (set in moduleSets) {
            set.tree.applyFilter(query)
            set.table.applyFilter(query)
        }
    }

    /** Reloads translation data in every panel. */
    fun refresh() {
        for (set in moduleSets) {
            set.tree.refresh()
            set.table.refresh()
            set.stats.refresh()
        }
    }

    /**
     * The empty state: where the plugin looked, what it found there, and the two ways out —
     * the wizard for someone who does not know the layout yet, the settings for someone who
     * does. `SetupWizardDialog` existed all along with nothing in the tool window leading to it.
     */
    private fun buildEmptyState(): JComponent {
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(16)
        }

        body.add(
            leftAligned(
                JBLabel(PluginBundle.message("toolwindow.empty.title")).apply {
                    font = font.deriveFont(Font.BOLD)
                }
            )
        )
        body.add(Box.createVerticalStrut(JBUI.scale(8)))

        val where = diagnostics.searchedPaths
            .ifEmpty { listOf(PluginBundle.message("toolwindow.empty.searched.everywhere")) }
            .joinToString(", ")
        body.add(leftAligned(secondaryLabel(PluginBundle.message("toolwindow.empty.searched", where))))
        body.add(leftAligned(secondaryLabel(PluginBundle.message("toolwindow.empty.found", diagnostics.sourceCount))))
        body.add(Box.createVerticalStrut(JBUI.scale(12)))

        body.add(leftAligned(link(PluginBundle.message("toolwindow.empty.wizard"), onRunWizard)))
        body.add(leftAligned(link(PluginBundle.message("toolwindow.empty.settings"), onOpenSettings)))

        // Pinned to the top: a Y_AXIS BoxLayout would otherwise centre the block vertically.
        return JPanel(BorderLayout()).apply { add(body, BorderLayout.NORTH) }
    }

    private fun secondaryLabel(text: String) = JBLabel(text).apply { foreground = secondaryForeground }

    /** The link fires on ACTIVATED only: the label also reports the cursor entering and leaving. */
    private fun link(text: String, onClick: () -> Unit) = HyperlinkLabel(text).apply {
        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) onClick()
        }
    }

    /** BoxLayout centres its children by default, which reads as a ragged left edge. */
    private fun <T : JComponent> leftAligned(child: T): T =
        child.apply { alignmentX = Component.LEFT_ALIGNMENT }

    private companion object {

        fun newPanelSet(project: Project, config: ModuleConfig?) = ModulePanelSet(
            config,
            TreeViewPanel(project, config),
            TableViewPanel(project, config),
            TranslationStatsPanel(project, config)
        )
    }
}
