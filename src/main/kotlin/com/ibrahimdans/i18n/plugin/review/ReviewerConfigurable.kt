package com.ibrahimdans.i18n.plugin.review

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * *Settings | Tools | i18n Support Plus | Reviews*: the name reviews are signed with.
 *
 * An application page: the name belongs to the person, not to the project. Edits a copy, written on
 * *Apply* only.
 */
class ReviewerConfigurable : SearchableConfigurable {

    private var panel: DialogPanel? = null

    override fun getId(): String = "preference.i18nPlugin.reviews"

    override fun getDisplayName(): String = PluginBundle.message("settings.reviews.title")

    override fun createComponent(): JComponent {
        val settings = ReviewerSettings.getInstance()
        return panel {
            row(PluginBundle.message("settings.reviews.name")) {
                textField()
                    .bindText({ settings.name }, { settings.name = it })
                    .align(AlignX.FILL)
                    .comment(PluginBundle.message("settings.reviews.name.comment"))
            }
        }.also { panel = it }
    }

    override fun isModified(): Boolean = panel?.isModified() == true

    override fun apply() {
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
