package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.preview.EditorRefresh
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.I18nToolWindowPanel
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.SimpleListCellRenderer

/**
 * Switches the modules put aside in one settings change: one application of a monorepo active, the
 * others aside ([focus]), or all of them back ([activateAll]). Each change is followed by what
 * *Apply* in the settings brings — the editors re-annotated, the tool window reloaded — and by a
 * notification whose *Undo* gives every module back the state it had before.
 */
object ModuleFocus {

    private const val TOOL_WINDOW_ID = "I18n"

    /** Keeps [module] active and puts every other module aside. Nothing happens when it is no module of the settings. */
    fun focus(project: Project, module: ModuleConfig) {
        val modules = Settings.getInstance(project).config().modules
        val index = modules.indexOf(module).takeIf { it >= 0 } ?: return
        change(project, ModuleSources.focus(modules, index), PluginBundle.message("modules.focus.done", module.name.ifBlank { module.rootDirectory }))
    }

    /** Makes every module active again. */
    fun activateAll(project: Project) {
        val modules = Settings.getInstance(project).config().modules
        change(project, ModuleSources.activateAll(modules), PluginBundle.message("modules.activate.all.done"))
    }

    /** [modules] stored as the settings' modules, the editors and the tool window refreshed, an *Undo* offered. */
    internal fun change(project: Project, modules: List<ModuleConfig>, message: String) {
        val settings = Settings.getInstance(project)
        val before = settings.config().modules.map { it.enabled }
        store(project, modules)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("i18n Support Plus")
            .createNotification(message, NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring(PluginBundle.message("modules.undo")) { undo(project, before) })
            .notify(project)
    }

    /** Gives every module back its state from [before]; see [ModuleSources.withEnabled]. */
    internal fun undo(project: Project, before: List<Boolean>) {
        store(project, ModuleSources.withEnabled(Settings.getInstance(project).config().modules, before))
    }

    private fun store(project: Project, modules: List<ModuleConfig>) {
        val settings = Settings.getInstance(project)
        settings.setConfig(settings.config().copy(modules = modules))
        EditorRefresh.afterSettingsChange(project)
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.contentManager?.contents
            ?.mapNotNull { it.component as? I18nToolWindowPanel }
            ?.forEach { it.refresh() }
    }

    /** Two modules at least: with one, there is nothing to focus on. */
    internal fun isAvailable(project: Project?): Boolean =
        project != null && Settings.getInstance(project).config().modules.size >= 2
}

/**
 * *Focus on a Module*: picks one module of the settings, keeps it active and puts the others
 * aside — the module selector's right-click menu and choosing a greyed entry do the same for the
 * module it names. Hidden in a project with fewer than two modules.
 */
class FocusModuleAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = ModuleFocus.isAvailable(e.project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val modules = Settings.getInstance(project).config().modules
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(modules)
            .setTitle(PluginBundle.message("modules.focus.choose"))
            .setRenderer(SimpleListCellRenderer.create { label, module, _ ->
                label.text = module?.name?.ifBlank { module.rootDirectory }.orEmpty()
            })
            .setItemChosenCallback { ModuleFocus.focus(project, it) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    companion object {
        /** Must match the `id` the action is registered under in plugin.xml. */
        const val ID = "com.ibrahimdans.i18n.FocusModule"
    }
}

/** *Activate All Modules*: every module put aside is active again. Hidden while none is put aside. */
class ActivateAllModulesAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = ModuleFocus.isAvailable(project) &&
            Settings.getInstance(project!!).config().modules.any { !it.enabled }
    }

    override fun actionPerformed(e: AnActionEvent) {
        ModuleFocus.activateAll(e.project ?: return)
    }

    companion object {
        /** Must match the `id` the action is registered under in plugin.xml. */
        const val ID = "com.ibrahimdans.i18n.ActivateAllModules"
    }
}
