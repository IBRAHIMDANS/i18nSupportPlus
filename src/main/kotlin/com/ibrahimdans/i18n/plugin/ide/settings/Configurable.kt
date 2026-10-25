package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.options.BaseConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.Nls
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * *Settings | Tools | i18n Support Plus*: the project's settings. The modules and the key assistance
 * rules are on the pages below it, [ModulesConfigurable] and [RulesConfigurable].
 */
class Configurable(val project: Project) : BaseConfigurable(), SearchableConfigurable {

    private var gui: JPanel? = null
    private var snapshot: Config? = null

    override fun createComponent(): JComponent {
        snapshot = Settings.getInstance(project).config()
        val page = SettingsPanel(Settings.getInstance(project), project)
        val root = page.getRootPanel()
        gui = root
        LocaleChoice.discover(project, alive = { gui === root }) { page.offerLocales(it) }
        return root
    }

    @Nls
    override fun getDisplayName(): String = PluginBundle.getMessage("app.name")

    /** No help page exists: a topic named here gave the dialog's *?* button nowhere to go. */
    override fun getHelpTopic(): String? = null

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