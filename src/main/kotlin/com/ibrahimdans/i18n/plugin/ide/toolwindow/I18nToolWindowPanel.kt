package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Main panel for the I18n tool window.
 * Contains a toolbar with Add/Refresh/Settings actions, a real-time search field,
 * and a tabbed pane with Tree and Table views.
 *
 * When 2 or more modules are configured in Settings, an additional top-level tab layer
 * is added — one tab per module. Each module tab contains its own Tree/Table/Stats panels
 * filtered to that module's rootDirectory. With 0 or 1 module, the original single-level
 * layout is preserved unchanged.
 */
class I18nToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val searchField = SearchTextField()

    // Panels used in single-level mode (no modules or 1 module)
    private var singleTreePanel: TreeViewPanel? = null
    private var singleTablePanel: TableViewPanel? = null
    private var singleStatsPanel: TranslationStatsPanel? = null

    // Panels used in multi-module mode (one set per module tab)
    private var modulePanels: List<ModulePanelSet> = emptyList()

    init {
        toolbar = buildTopBar()
        buildContent()
        connectSearchField()
        refresh()
    }

    // ---------------------------------------------------------------------------
    // Content building
    // ---------------------------------------------------------------------------

    /**
     * Builds the main content area based on the current module configuration.
     * Called once during init; call [refresh] to reload data.
     */
    private fun buildContent() {
        val modules = Settings.getInstance(project).modules
        if (modules.size >= 2) {
            buildMultiModuleContent(modules)
        } else {
            buildSingleLevelContent()
        }
    }

    /** Original 3-tab layout (Tree / Table / Stats). */
    private fun buildSingleLevelContent() {
        val tree = TreeViewPanel(project)
        val table = TableViewPanel(project)
        val stats = TranslationStatsPanel(project)
        singleTreePanel = tree
        singleTablePanel = table
        singleStatsPanel = stats
        modulePanels = emptyList()

        val tabbedPane = JBTabbedPane()
        tabbedPane.addTab("Tree", tree)
        tabbedPane.addTab("Table", table)
        tabbedPane.addTab("Stats", stats)
        setContent(tabbedPane)
    }

    /**
     * Multi-module layout: outer tab per module, each containing Tree/Table/Stats inner tabs.
     */
    private fun buildMultiModuleContent(modules: MutableList<ModuleConfig>) {
        singleTreePanel = null
        singleTablePanel = null
        singleStatsPanel = null

        val sets = modules.map { cfg ->
            val tree = TreeViewPanel(project, cfg)
            val table = TableViewPanel(project, cfg)
            val stats = TranslationStatsPanel(project, cfg)
            ModulePanelSet(cfg, tree, table, stats)
        }
        modulePanels = sets

        val outerTabs = JBTabbedPane()
        for (set in sets) {
            val innerTabs = JBTabbedPane()
            innerTabs.addTab("Tree", set.tree)
            innerTabs.addTab("Table", set.table)
            innerTabs.addTab("Stats", set.stats)
            val tabTitle = set.config.name.ifBlank { "Module" }
            outerTabs.addTab(tabTitle, innerTabs)
        }
        setContent(outerTabs)
    }

    // ---------------------------------------------------------------------------
    // Search field
    // ---------------------------------------------------------------------------

    private fun connectSearchField() {
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onSearch()
            override fun removeUpdate(e: DocumentEvent) = onSearch()
            override fun changedUpdate(e: DocumentEvent) = onSearch()
        })
    }

    private fun onSearch() {
        val query = searchField.text.orEmpty()
        singleTreePanel?.applyFilter(query)
        singleTablePanel?.applyFilter(query)
        for (set in modulePanels) {
            set.tree.applyFilter(query)
            set.table.applyFilter(query)
        }
    }

    // ---------------------------------------------------------------------------
    // Toolbar
    // ---------------------------------------------------------------------------

    /**
     * Builds a top bar combining the action toolbar and the search field.
     */
    private fun buildTopBar(): javax.swing.JComponent {
        val group = DefaultActionGroup()

        group.add(object : AnAction("Add Translation", "Add a new translation key", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) {
                val fullKey = FullKey(source = "", ns = null, compositeKey = emptyList())
                val dialog = TranslationDialog(project, fullKey, Mode.CREATE)
                if (dialog.showAndGet()) {
                    refresh()
                }
            }
        })

        group.add(object : AnAction("Add Namespace", "Create a new translation namespace", AllIcons.Nodes.Package) {
            override fun actionPerformed(e: AnActionEvent) {
                val input = Messages.showInputDialog(
                    project,
                    "Namespace name (letters, digits, hyphens):",
                    "Add Namespace",
                    null
                )?.trim() ?: return
                if (input.isBlank()) return
                if (!input.matches(Regex("[a-zA-Z0-9-]+"))) {
                    Messages.showErrorDialog(
                        project,
                        "Invalid namespace name. Only letters, digits and hyphens are allowed.",
                        "Invalid Name"
                    )
                    return
                }
                DialogViewModel(project).createNamespace(input)
                refresh()
            }
        })

        group.add(object : AnAction("Refresh", "Reload all translations", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refresh()
            }
        })

        group.add(object : AnAction("Sync Keys", "Sync missing keys to all locales", AllIcons.General.Modified) {
            override fun actionPerformed(e: AnActionEvent) {
                KeysSynchronizer().sync(project)
            }
        })

        group.add(object : AnAction("Settings", "Open i18n Support Plus settings", AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "i18n Support Plus Configuration")
            }
        })

        val actionToolbar = ActionManager.getInstance().createActionToolbar("I18nToolWindow", group, true)
        actionToolbar.targetComponent = actionToolbar.component

        searchField.textEditor.toolTipText = "Filter by key or translation value"

        val topBar = JPanel(BorderLayout())
        topBar.add(actionToolbar.component, BorderLayout.WEST)
        topBar.add(searchField, BorderLayout.CENTER)
        return topBar
    }

    // ---------------------------------------------------------------------------
    // Refresh
    // ---------------------------------------------------------------------------

    /**
     * Reloads translation data in all active panels.
     * Refreshes single-level panels or all module panel sets, whichever is active.
     */
    fun refresh() {
        singleTreePanel?.refresh()
        singleTablePanel?.refresh()
        singleStatsPanel?.refresh()
        for (set in modulePanels) {
            set.tree.refresh()
            set.table.refresh()
            set.stats.refresh()
        }
    }

    // ---------------------------------------------------------------------------
    // Data holder
    // ---------------------------------------------------------------------------

    /** Groups the three panels associated with a single module tab. */
    private data class ModulePanelSet(
        val config: ModuleConfig,
        val tree: TreeViewPanel,
        val table: TableViewPanel,
        val stats: TranslationStatsPanel
    )
}
