package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.actions.ModuleFocus
import com.ibrahimdans.i18n.plugin.ide.actions.SyncKeysAction
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SimpleListCellRenderer
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/**
 * Ids of the registered actions the toolbar hosts, in display order. Unknown ids are skipped —
 * see [ToolWindowToolbar.addHostedActions]. `ScanOrphanKeys` is the one TASK-UX-TABLE-STATUS
 * publishes; it appears the day that declaration reaches plugin.xml.
 */
private val HOSTED_ACTION_IDS = listOf(
    SyncKeysAction.ID,
    "com.ibrahimdans.i18n.ScanOrphanKeys"
)

/**
 * The actions of the tool window's top bar: the three view toggles, *Add Translation*, *Add
 * Namespace*, *Refresh*, the registered actions it hosts and *Settings*.
 *
 * [shell] is the content on screen, null until the diagnostics came back; [onRefresh] reloads the
 * tool window after an action changed the translations.
 */
internal class ToolWindowToolbar(
    private val project: Project,
    private val shell: () -> ShellContent?,
    private val onRefresh: () -> Unit,
) {

    /**
     * The action toolbar, its data context taken from [target], the tool window content: not
     * from the toolbar itself (self-reference breaks context lookups and anchors action tooltips
     * to the wrong component).
     */
    fun create(target: JComponent): ActionToolbar {
        val group = DefaultActionGroup()
        for (view in ToolWindowTab.entries) group.add(ViewToggle(view))
        group.addSeparator()
        group.add(addTranslationAction())
        group.add(addNamespaceAction())
        group.add(refreshAction())
        addHostedActions(group)
        group.add(settingsAction())

        val actionToolbar = ActionManager.getInstance().createActionToolbar("I18nToolWindow", group, true)
        actionToolbar.targetComponent = target
        return actionToolbar
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
            val dialog = TranslationDialog(project, fullKey, Mode.CREATE, tabModule = shell()?.activeModule)
            if (dialog.showAndGet()) {
                onRefresh()
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
            onRefresh()
        }
    }

    private fun refreshAction() = object : AnAction(
        PluginBundle.message("toolwindow.action.refresh"),
        PluginBundle.message("toolwindow.action.refresh.description"),
        AllIcons.Actions.Refresh
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            onRefresh()
        }
    }

    private fun settingsAction() = object : AnAction(
        PluginBundle.message("toolwindow.action.settings"),
        PluginBundle.message("toolwindow.action.settings.description"),
        AllIcons.General.Settings
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            openPluginSettings(project)
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
            e.presentation.isVisible = shell()?.hasViews == true
        }

        override fun isSelected(e: AnActionEvent) = shell()?.selectedView == view

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            // A toggle clicked again stays on: one view is always shown.
            if (state) shell()?.selectedView = view
        }
    }
}

/**
 * The module selector of the tool window's top bar, which replaces the outer row of tabs: the
 * modules on screen, then the ones put aside, greyed. Hidden with a single module.
 *
 * Choosing a module on screen calls [onShow] with its index; choosing one put aside focuses on it,
 * and the settings change rebuilds the shell. Right-clicking it focuses on the module it shows, or
 * brings every module back.
 */
internal class ModuleSelector(private val project: Project, private val onShow: (index: Int) -> Unit) {

    val component = ComboBox<ModuleSelectorItem>().apply {
        isVisible = false
        toolTipText = PluginBundle.message("toolwindow.module.selector.tooltip")
        // A module put aside is greyed: it has nothing on screen until it is focused on.
        renderer = SimpleListCellRenderer.create { label, item, _ ->
            label.text = item?.label.orEmpty()
            if (item?.aside == true) label.foreground = secondaryForeground
        }
    }

    /** Guards the listener against the programmatic repopulation of the model. */
    private var updating = false

    init {
        component.componentPopupMenu = popupMenu()
        component.addItemListener { event ->
            if (updating || event.stateChange != ItemEvent.SELECTED) return@addItemListener
            val item = component.selectedItem as? ModuleSelectorItem ?: return@addItemListener
            // Choosing a module put aside focuses on it; the settings change rebuilds the shell.
            if (item.aside) {
                ModuleFocus.focus(project, item.module)
                return@addItemListener
            }
            onShow(component.selectedIndex)
        }
    }

    /** Lists the modules [diagnostics] describes; pointless, and hidden, with a single module. */
    fun update(diagnostics: ShellDiagnostics) {
        val items = diagnostics.selectorItems
        updating = true
        try {
            component.model = DefaultComboBoxModel(items.toTypedArray())
            component.isVisible = items.isNotEmpty()
            if (items.isNotEmpty()) component.selectedIndex = 0
        } finally {
            updating = false
        }
    }

    /** Right-click on the selector: focus on the module it shows, or bring every module back. */
    private fun popupMenu(): JPopupMenu = JPopupMenu().apply {
        add(JMenuItem(PluginBundle.message("toolwindow.module.focus")).apply {
            addActionListener {
                // Qualified: inside a JPopupMenu, a bare `component` is the menu's own getComponent().
                (this@ModuleSelector.component.selectedItem as? ModuleSelectorItem)?.let { ModuleFocus.focus(project, it.module) }
            }
        })
        add(JMenuItem(PluginBundle.message("action.com.ibrahimdans.i18n.ActivateAllModules.text")).apply {
            addActionListener { ModuleFocus.activateAll(project) }
        })
    }
}
