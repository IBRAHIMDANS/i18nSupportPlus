package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.Configurable
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.settings.SetupWizardDialog
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ActionListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

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
internal val secondaryForeground: JBColor
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

    /** Switches the views to the module chosen; the result count is scoped to it, so it is counted again. */
    private val moduleSelector = ModuleSelector(project) { index ->
        shellContent?.showModule(index)
        searchIndex = null
        updateResultCount()
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
        moduleSelector.update(diagnostics)
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
        val actionToolbar = ToolWindowToolbar(project, { shellContent }, ::refresh).create(this)
        this.actionToolbar = actionToolbar

        searchField.textEditor.toolTipText = PluginBundle.message("toolwindow.search.tooltip")

        val trailing = JPanel(BorderLayout())
        trailing.add(resultCountLabel, BorderLayout.WEST)
        trailing.add(filterSlot, BorderLayout.CENTER)
        trailing.add(moduleSelector.component, BorderLayout.EAST)

        val topBar = JPanel(BorderLayout())
        topBar.add(actionToolbar.component, BorderLayout.WEST)
        topBar.add(searchField, BorderLayout.CENTER)
        topBar.add(trailing, BorderLayout.EAST)
        return topBar
    }

    private fun openSettings() = openPluginSettings(project)
}
