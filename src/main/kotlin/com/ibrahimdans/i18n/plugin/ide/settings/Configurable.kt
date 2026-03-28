package com.ibrahimdans.i18n.plugin.ide.settings

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

    override fun disposeUIResources() {
        gui = null
        snapshot = null
    }
}