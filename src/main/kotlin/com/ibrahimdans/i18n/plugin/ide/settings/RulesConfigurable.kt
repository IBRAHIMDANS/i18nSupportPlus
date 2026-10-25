package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.annotations.Nls
import javax.swing.JComponent

/**
 * *Settings | Tools | i18n Support Plus | Key Assistance Rules*: the calls that behave like `t`.
 *
 * Like the main page, it writes to [Settings] as it is typed and compares the rules with a
 * [snapshot] for *Apply*.
 */
class RulesConfigurable(private val project: Project) : SearchableConfigurable {

    private var snapshot: List<EditorRuleState>? = null

    override fun getId(): String = ID

    @Nls
    override fun getDisplayName(): String = PluginBundle.message("settings.rules.page.title")

    override fun createComponent(): JComponent {
        val settings = Settings.getInstance(project)
        snapshot = settings.config().rules
        return panel {
            row { cell(RulesEditorPanel(settings)).align(Align.FILL) }.resizableRow()
        }
    }

    override fun isModified(): Boolean = Settings.getInstance(project).config().rules != snapshot

    override fun apply() {
        snapshot = Settings.getInstance(project).config().rules
    }

    override fun disposeUIResources() {
        snapshot = null
    }

    companion object {
        const val ID = "preference.i18nPlugin.rules"
    }
}
