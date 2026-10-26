package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.preview.EditorRefresh
import com.ibrahimdans.i18n.plugin.ide.toolwindow.ToolWindowViewState
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.annotations.Nls
import javax.swing.JComponent

/**
 * *Settings | Tools | i18n Support Plus | Modules*: the applications of a monorepo.
 *
 * Like the main page, it writes to [Settings] as it is typed. [snapshot] holds the modules last
 * validated, which *Cancel* puts back; [opened], the ones the dialog opened with.
 */
class ModulesConfigurable(private val project: Project) : SearchableConfigurable {

    private var opened: List<ModuleConfig>? = null
    private var snapshot: List<ModuleConfig>? = null
    private var editor: ModulesEditorPanel? = null

    override fun getId(): String = ID

    @Nls
    override fun getDisplayName(): String = PluginBundle.message("settings.modules.page.title")

    override fun createComponent(): JComponent {
        val settings = Settings.getInstance(project)
        opened = settings.config().modules
        snapshot = opened
        val modules = ModulesEditorPanel(settings, project).also { editor = it }
        LocaleChoice.discover(project, alive = { editor === modules }) { modules.offerLocales(it) }
        return panel {
            row { cell(modules).align(Align.FILL) }.resizableRow()
        }
    }

    override fun isModified(): Boolean = Settings.getInstance(project).config().modules != snapshot

    override fun apply() {
        snapshot = Settings.getInstance(project).config().modules
    }

    /**
     * *OK* has applied by now; modules still apart from [snapshot] were left by *Cancel* and go back.
     *
     * The modules kept are then compared with the [opened] ones, when a renamed module has its
     * final name: following each keystroke would move a module's view state through the names typed
     * on the way, one of which may be another module's.
     */
    override fun disposeUIResources() {
        val before = opened
        val kept = snapshot
        if (before != null && kept != null) {
            val settings = Settings.getInstance(project)
            val current = settings.config()
            if (current.modules != kept) {
                settings.setConfig(current.copy(modules = kept))
                EditorRefresh.afterSettingsChange(project)
            }
            val viewState = ToolWindowViewState.getInstance(project)
            ToolWindowViewState.renamedModules(before, kept)
                .forEach { (from, to) -> viewState.moveModuleState(from, to) }
        }
        editor = null
        opened = null
        snapshot = null
    }

    companion object {
        const val ID = "preference.i18nPlugin.modules"
    }
}
