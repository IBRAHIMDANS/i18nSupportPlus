package com.ibrahimdans.i18n.plugin.ide.settings

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
 * Like the main page, it writes to [Settings] as it is typed and compares the modules with a
 * [snapshot] for *Apply*.
 */
class ModulesConfigurable(private val project: Project) : SearchableConfigurable {

    private var snapshot: List<ModuleConfig>? = null
    private var editor: ModulesEditorPanel? = null

    override fun getId(): String = ID

    @Nls
    override fun getDisplayName(): String = PluginBundle.message("settings.modules.page.title")

    override fun createComponent(): JComponent {
        val settings = Settings.getInstance(project)
        snapshot = settings.config().modules
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
     * The modules are compared with the [snapshot] once the dialog closes, when a renamed module
     * has its final name: following each keystroke would move a module's view state through the
     * names typed on the way, one of which may be another module's.
     */
    override fun disposeUIResources() {
        snapshot?.let { before ->
            val viewState = ToolWindowViewState.getInstance(project)
            ToolWindowViewState.renamedModules(before, Settings.getInstance(project).config().modules)
                .forEach { (from, to) -> viewState.moveModuleState(from, to) }
        }
        editor = null
        snapshot = null
    }

    companion object {
        const val ID = "preference.i18nPlugin.modules"
    }
}
