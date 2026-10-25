package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.actions.ModuleFocus
import com.ibrahimdans.i18n.plugin.ide.actions.SyncKeysAction
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Configurable
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.settings.SetupWizardDialog
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.ActionListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ItemEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent

/** ~150 ms: long enough to swallow a burst of keystrokes, short enough to still feel live. */
internal const val SEARCH_DELAY_MS = 150

/**
 * Secondary text taken from the colour scheme rather than from a fixed grey: a hard-coded
 * colour ignores custom themes and the high-contrast one, which is exactly the defect this
 * panel's siblings are being cleaned of.
 *
 * A getter, not a stored value: [JBColor.namedColor] is cheap, and resolving it lazily keeps
 * the file loadable in a container where the look and feel is not installed yet.
 */
private val secondaryForeground: JBColor
    get() = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)

/**
 * Opens the plugin's settings page — by class, not by display name: the name changed once
 * already, and the dialog then opened on whatever page it pleased. Outside the panel, which a
 * test cannot build, so the call can be checked.
 */
internal fun openPluginSettings(project: Project) =
    ShowSettingsUtil.getInstance().showSettingsDialog(project, Configurable::class.java)

/** The name a view goes by in the toolbar. */
internal fun ToolWindowTab.label(): String = when (this) {
    ToolWindowTab.TREE -> PluginBundle.message("toolwindow.tab.tree")
    ToolWindowTab.TABLE -> PluginBundle.message("toolwindow.tab.table")
    ToolWindowTab.STATS -> PluginBundle.message("toolwindow.tab.stats")
}

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

/**
 * The result count shown next to the search field.
 *
 * Counts entries exactly as the tree and the table filter them — the key or any value
 * containing the query, case-insensitively — so the number matches what the panels display.
 */
internal object SearchResults {

    /** The number of keys [query] keeps; the whole set when nothing is searched. */
    fun count(translations: Map<String, Map<String, String>>, query: String): Int {
        if (query.isBlank()) return translations.size
        val needle = query.lowercase()
        return translations.count { (key, values) ->
            key.lowercase().contains(needle) || values.values.any { it.lowercase().contains(needle) }
        }
    }

    /**
     * The label for [count] results. Empty while nothing is searched: a count is an answer,
     * and it needs a question to mean anything.
     */
    fun label(count: Int, query: String): String = when {
        query.isBlank() -> ""
        count == 0 -> PluginBundle.message("toolwindow.search.results.none")
        count == 1 -> PluginBundle.message("toolwindow.search.results.one")
        else -> PluginBundle.message("toolwindow.search.results.many", count)
    }
}

/**
 * Runs an action after a delay, dropping whatever was still pending.
 *
 * Injected into [I18nToolWindowPanel] so the debounce can be driven by a test without a real
 * clock: a test runner keeps the action and fires it on demand.
 */
internal fun interface DelayedRunner {
    fun schedule(delayMs: Int, action: () -> Unit)
}

/**
 * The production runner: a restartable [Timer], which fires on the EDT — the filter it defers
 * rebuilds Swing models, so it may not land anywhere else.
 */
internal class SwingDelayedRunner : DelayedRunner {

    private var timer: Timer? = null

    override fun schedule(delayMs: Int, action: () -> Unit) {
        timer?.stop()
        timer = Timer(delayMs, ActionListener { action() }).apply {
            isRepeats = false
            start()
        }
    }
}

/**
 * Turns keystrokes into filter passes.
 *
 * Filtering on every keystroke rebuilt the whole tree and refiltered the table on the EDT,
 * character by character. The pass is deferred by [delayMs] instead, and only the last query
 * of a burst survives.
 */
internal class SearchDebouncer(
    private val runner: DelayedRunner,
    private val delayMs: Int = SEARCH_DELAY_MS,
    private val onReady: (String) -> Unit
) {
    fun onQueryChanged(query: String) = runner.schedule(delayMs) { onReady(query) }
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

/**
 * Main panel for the I18n tool window.
 *
 * Holds the single toolbar — view toggles, actions, search field and result count, the table's
 * filters when they fit, module selector — above the view on screen, or above the empty state
 * when the project has no translation the plugin can read.
 *
 * [searchDelay] is injected so the search debounce can be exercised without a real clock. The
 * constructor taking it is internal only because [DelayedRunner] is — the tool window uses the
 * single-argument one.
 */
class I18nToolWindowPanel internal constructor(
    private val project: Project,
    searchDelay: DelayedRunner
) : SimpleToolWindowPanel(true, true) {

    constructor(project: Project) : this(project, SwingDelayedRunner())

    private val searchField = SearchTextField()

    private val resultCountLabel = JBLabel().apply {
        foreground = secondaryForeground
        border = JBUI.Borders.empty(0, 6)
    }

    private val moduleSelector = ComboBox<ModuleSelectorItem>().apply {
        isVisible = false
        toolTipText = PluginBundle.message("toolwindow.module.selector.tooltip")
        // A module put aside is greyed: it has nothing on screen until it is focused on.
        renderer = SimpleListCellRenderer.create { label, item, _ ->
            label.text = item?.label.orEmpty()
            if (item?.aside == true) label.foreground = secondaryForeground
        }
    }

    /** Where the table's filters go when the window is wide enough; see [ShellContent.fitToWidth]. */
    private val filterSlot = JPanel(BorderLayout()).apply {
        isVisible = false
        border = JBUI.Borders.emptyLeft(6)
    }

    private val searchDebouncer = SearchDebouncer(searchDelay) { applySearch(it) }

    private var shellContent: ShellContent? = null

    /** Kept to repaint the view toggles when the view changes without them, as at a rebuild. */
    private var actionToolbar: ActionToolbar? = null

    /** Guards the selector's listener against the programmatic repopulation of its model. */
    private var updatingSelector = false

    /**
     * Keys and values of the visible module, used only to count search results. Loaded on
     * demand — a project nobody searches never pays for it — and dropped on every refresh.
     */
    private var searchIndex: Map<String, Map<String, String>>? = null

    init {
        toolbar = buildTopBar()
        // Something must be on screen before the diagnostics come back from the background.
        setContent(loadingPlaceholder())
        connectSearchField()
        connectModuleSelector()
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                shellContent?.fitToWidth(width)
            }
        })
        refresh()
    }

    // ---------------------------------------------------------------------------
    // Refresh and content building
    // ---------------------------------------------------------------------------

    /**
     * Reloads the shell: the diagnostics first, off the EDT because they scan the project,
     * then the panels. The content itself is rebuilt only when the diagnostics describe a
     * different shell — see [shellNeedsRebuild].
     */
    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val diagnostics = collectDiagnostics()
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                applyDiagnostics(diagnostics)
            }
        }
    }

    private fun collectDiagnostics(): ShellDiagnostics {
        val config = Settings.getInstance(project).config()
        return ShellDiagnostics(
            searchedPaths = ShellDiagnostics.searchedPaths(config),
            // Project-wide on purpose: the question the empty state answers is whether the
            // plugin can read anything at all, not whether one module resolves.
            sourceCount = TranslationDataLoader.findSources(project).size,
            // A module put aside has no file to show: it gets no panels, only a greyed selector entry.
            modules = ModuleSources.active(config.modules),
            asideModules = config.modules.filterNot { it.enabled }
        )
    }

    private fun applyDiagnostics(diagnostics: ShellDiagnostics) {
        searchIndex = null
        if (shellNeedsRebuild(shellContent?.diagnostics, diagnostics)) {
            rebuildContent(diagnostics)
        }
        shellContent?.refresh()
        updateResultCount()
    }

    private fun rebuildContent(diagnostics: ShellDiagnostics) {
        val built = ShellContent(
            project = project,
            diagnostics = diagnostics,
            onRunWizard = { SetupWizardDialog(project).show() },
            onOpenSettings = { openSettings() },
            viewState = ToolWindowViewState.getInstance(project),
            onClearSearch = { searchField.text = "" },
            filterSlot = filterSlot
        )
        shellContent = built
        setContent(built.component)
        built.applyFilter(searchField.text.orEmpty())
        built.fitToWidth(width)
        updateModuleSelector(diagnostics)
        actionToolbar?.updateActionsAsync()
        revalidate()
        repaint()
    }

    private fun loadingPlaceholder(): JComponent = JPanel(BorderLayout()).apply {
        add(
            JBLabel(PluginBundle.message("toolwindow.loading")).apply {
                foreground = secondaryForeground
                border = JBUI.Borders.empty(16)
            },
            BorderLayout.NORTH
        )
    }

    // ---------------------------------------------------------------------------
    // Module selector
    // ---------------------------------------------------------------------------

    private fun connectModuleSelector() {
        moduleSelector.componentPopupMenu = modulePopupMenu()
        moduleSelector.addItemListener { event ->
            if (updatingSelector || event.stateChange != ItemEvent.SELECTED) return@addItemListener
            val item = moduleSelector.selectedItem as? ModuleSelectorItem ?: return@addItemListener
            // Choosing a module put aside focuses on it; the settings change rebuilds the shell.
            if (item.aside) {
                ModuleFocus.focus(project, item.module)
                return@addItemListener
            }
            shellContent?.showModule(moduleSelector.selectedIndex)
            // The count is scoped to the visible module, so it has to be recomputed.
            searchIndex = null
            updateResultCount()
        }
    }

    /** The selector replaces the outer row of tabs; it is pointless with a single module. */
    private fun updateModuleSelector(diagnostics: ShellDiagnostics) {
        val items = diagnostics.selectorItems
        updatingSelector = true
        try {
            moduleSelector.model = DefaultComboBoxModel(items.toTypedArray())
            moduleSelector.isVisible = items.isNotEmpty()
            if (items.isNotEmpty()) moduleSelector.selectedIndex = 0
        } finally {
            updatingSelector = false
        }
    }

    /** Right-click on the selector: focus on the module it shows, or bring every module back. */
    private fun modulePopupMenu(): JPopupMenu = JPopupMenu().apply {
        add(JMenuItem(PluginBundle.message("toolwindow.module.focus")).apply {
            addActionListener {
                (moduleSelector.selectedItem as? ModuleSelectorItem)?.let { ModuleFocus.focus(project, it.module) }
            }
        })
        add(JMenuItem(PluginBundle.message("action.com.ibrahimdans.i18n.ActivateAllModules.text")).apply {
            addActionListener { ModuleFocus.activateAll(project) }
        })
    }

    // ---------------------------------------------------------------------------
    // Search field
    // ---------------------------------------------------------------------------

    private fun connectSearchField() {
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onSearchTyped()
            override fun removeUpdate(e: DocumentEvent) = onSearchTyped()
            override fun changedUpdate(e: DocumentEvent) = onSearchTyped()
        })
    }

    private fun onSearchTyped() = searchDebouncer.onQueryChanged(searchField.text.orEmpty())

    private fun applySearch(query: String) {
        shellContent?.applyFilter(query)
        updateResultCount()
    }

    /**
     * Fills the count shown next to the field, loading the index in the background the first
     * time it is needed. With it, "no result" reads differently from "nothing configured" —
     * the two used to look the same: an empty tree.
     */
    private fun updateResultCount() {
        val query = searchField.text.orEmpty()
        if (query.isBlank()) {
            resultCountLabel.text = ""
            return
        }
        val index = searchIndex
        if (index != null) {
            resultCountLabel.text = SearchResults.label(SearchResults.count(index, query), query)
            return
        }
        val moduleConfig = shellContent?.activeModule
        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = TranslationDataLoader.loadAllTranslations(project, moduleConfig)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                searchIndex = loaded
                // The index is set, so this pass takes the branch above and stops there.
                updateResultCount()
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Toolbar
    // ---------------------------------------------------------------------------

    /**
     * The single home for the tool window's actions: the view toggles and the action toolbar,
     * the search field and its result count, the table's filters when they fit, and the module
     * selector — one row where there used to be three (toolbar, tabs, filters).
     */
    private fun buildTopBar(): JComponent {
        val group = DefaultActionGroup()
        for (view in ToolWindowTab.entries) group.add(ViewToggle(view))
        group.addSeparator()
        group.add(addTranslationAction())
        group.add(addNamespaceAction())
        group.add(refreshAction())
        addHostedActions(group)
        group.add(settingsAction())

        val actionToolbar = ActionManager.getInstance().createActionToolbar("I18nToolWindow", group, true)
        // The toolbar's data context must come from the tool window content,
        // not from the toolbar itself (self-reference breaks context lookups
        // and anchors action tooltips to the wrong component).
        actionToolbar.targetComponent = this
        this.actionToolbar = actionToolbar

        searchField.textEditor.toolTipText = PluginBundle.message("toolwindow.search.tooltip")

        val trailing = JPanel(BorderLayout())
        trailing.add(resultCountLabel, BorderLayout.WEST)
        trailing.add(filterSlot, BorderLayout.CENTER)
        trailing.add(moduleSelector, BorderLayout.EAST)

        val topBar = JPanel(BorderLayout())
        topBar.add(actionToolbar.component, BorderLayout.WEST)
        topBar.add(searchField, BorderLayout.CENTER)
        topBar.add(trailing, BorderLayout.EAST)
        return topBar
    }

    /**
     * Adds the registered actions the toolbar hosts, skipping the ids the platform does not
     * know.
     *
     * Tolerant by design: the panels are handing their own buttons over to this toolbar
     * (TASK-UX-TABLE-STATUS turns "Scan Orphans" into a published action, TASK-UX-STATS-PANEL
     * drops its duplicate Refresh), and those `<action>` declarations land in plugin.xml with
     * their own change. Until then [ActionManager.getAction] returns null for their ids and
     * the toolbar must still build — an id nobody registered is a button that does not appear,
     * never an exception at start-up.
     */
    private fun addHostedActions(group: DefaultActionGroup) {
        val manager = ActionManager.getInstance()
        for (id in HOSTED_ACTION_IDS) {
            manager.getAction(id)?.let { group.add(it) }
        }
    }

    private fun addTranslationAction() = object : AnAction(
        PluginBundle.message("toolwindow.action.add.translation"),
        PluginBundle.message("toolwindow.action.add.translation.description"),
        AllIcons.General.Add
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val fullKey = FullKey(source = "", ns = null, compositeKey = emptyList())
            val dialog = TranslationDialog(project, fullKey, Mode.CREATE)
            if (dialog.showAndGet()) {
                refresh()
            }
        }
    }

    private fun addNamespaceAction() = object : AnAction(
        PluginBundle.message("toolwindow.action.add.namespace"),
        PluginBundle.message("toolwindow.action.add.namespace.description"),
        AllIcons.Nodes.Package
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val input = Messages.showInputDialog(
                project,
                PluginBundle.message("toolwindow.action.add.namespace.prompt"),
                PluginBundle.message("toolwindow.action.add.namespace"),
                null
            )?.trim() ?: return
            if (input.isBlank()) return
            if (!input.matches(Regex("[a-zA-Z0-9-]+"))) {
                Messages.showErrorDialog(
                    project,
                    PluginBundle.message("toolwindow.action.add.namespace.invalid"),
                    PluginBundle.message("toolwindow.action.add.namespace.invalid.title")
                )
                return
            }
            DialogViewModel(project).createNamespace(input)
            refresh()
        }
    }

    private fun refreshAction() = object : AnAction(
        PluginBundle.message("toolwindow.action.refresh"),
        PluginBundle.message("toolwindow.action.refresh.description"),
        AllIcons.Actions.Refresh
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            refresh()
        }
    }

    private fun settingsAction() = object : AnAction(
        PluginBundle.message("toolwindow.action.settings"),
        PluginBundle.message("toolwindow.action.settings.description"),
        AllIcons.General.Settings
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            openSettings()
        }
    }

    /**
     * Shows one of the three views. Text toggles rather than a tab row of their own: they sit
     * with the other actions, so the views cost no height. Hidden on the empty state, which has
     * no view to show.
     */
    private inner class ViewToggle(private val view: ToolWindowTab) : ToggleAction(view.label()), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun displayTextInToolbar() = true

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isVisible = shellContent?.hasViews == true
        }

        override fun isSelected(e: AnActionEvent) = shellContent?.selectedView == view

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            // A toggle clicked again stays on: one view is always shown.
            if (state) shellContent?.selectedView = view
        }
    }

    private fun openSettings() = openPluginSettings(project)

    private companion object {

        /**
         * Ids of the registered actions the toolbar hosts, in display order. Unknown ids are
         * skipped — see [addHostedActions]. `ScanOrphanKeys` is the one TASK-UX-TABLE-STATUS
         * publishes; it appears the day that declaration reaches plugin.xml.
         */
        val HOSTED_ACTION_IDS = listOf(
            SyncKeysAction.ID,
            "com.ibrahimdans.i18n.ScanOrphanKeys"
        )
    }
}
