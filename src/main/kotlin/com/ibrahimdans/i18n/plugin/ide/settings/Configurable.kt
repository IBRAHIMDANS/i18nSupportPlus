package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.toolwindow.ToolWindowViewState
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.options.BaseConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.Nls
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Project configurable
 */
class Configurable(val project: Project) : BaseConfigurable(), SearchableConfigurable {

    private var gui: JPanel? = null
    private var snapshot: Config? = null

    override fun createComponent(): JComponent {
        snapshot = Settings.getInstance(project).config()
        gui = SettingsPanel(Settings.getInstance(project), project).getRootPanel()
        return gui!!
    }

    @Nls
    override fun getDisplayName(): String = PluginBundle.getMessage("app.name")

    override fun getHelpTopic(): String? = "preference.i18nPlugin"

    override fun getId(): String = "preference.i18nPlugin"

    override fun isModified(): Boolean = Settings.getInstance(project).config() != snapshot

    override fun apply() {
        snapshot = Settings.getInstance(project).config()
    }

    /**
     * Edits are written to [Settings] as they are typed, so the modules are compared with the
     * [snapshot] once the dialog closes, when a renamed module has its final name: following each
     * keystroke would move a module's view state through the names typed on the way, one of
     * which may be another module's.
     */
    override fun disposeUIResources() {
        snapshot?.let { before ->
            val viewState = ToolWindowViewState.getInstance(project)
            ToolWindowViewState.renamedModules(before.modules, Settings.getInstance(project).config().modules)
                .forEach { (from, to) -> viewState.moveModuleState(from, to) }
        }
        gui = null
        snapshot = null
    }
}