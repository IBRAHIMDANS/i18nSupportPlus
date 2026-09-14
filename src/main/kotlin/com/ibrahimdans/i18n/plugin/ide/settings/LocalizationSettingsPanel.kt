package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ConfigurationProperty
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.panel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Settings each translation format declares for itself, through `Localization.config().props()`.
 *
 * They are stored in [Settings.localizationConfig] under `<localization id>/<property id>` and read
 * back with [Config.getLocalizationSetting]. Both halves existed with nothing declaring a property,
 * nothing showing one and nothing reading one. One field per declared property is generated here,
 * so a format adding a setting needs no change to the settings form.
 */
internal class LocalizationSettingsPanel(private val settings: Settings) {

    /** The form, or null when no format declares any setting. */
    fun build(): JPanel? {
        val declared = Extensions.LOCALIZATION.extensionList
            .map { it.config() }
            .filter { it.props().isNotEmpty() }
        if (declared.isEmpty()) return null
        return panel {
            declared.forEach { localization ->
                localization.props().forEach { property ->
                    row("${localization.id().uppercase()} — ${property.displayName}") {
                        cell(field(localization.id(), property))
                    }
                }
            }
        }
    }

    private fun field(localizationId: String, property: ConfigurationProperty): JTextField {
        val key = storageKey(localizationId, property.id)
        val field = JTextField(settings.localizationConfig[key] ?: property.defaultValue, 8)
        field.name = key
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = store(key, field.text)
            override fun removeUpdate(e: DocumentEvent) = store(key, field.text)
            override fun changedUpdate(e: DocumentEvent) = store(key, field.text)
        })
        return field
    }

    private fun store(key: String, value: String) {
        settings.localizationConfig = settings.localizationConfig + (key to value.trim())
    }

    companion object {
        /** Where a format's setting is stored in [Settings.localizationConfig]. */
        fun storageKey(localizationId: String, propertyId: String): String = "$localizationId/$propertyId"

        /** Id of the indentation property a format may declare (YAML does). */
        const val INDENT = "indent"

        private const val DEFAULT_INDENT = 2
        private const val MAX_INDENT = 8

        /**
         * The indentation generated keys use in [localizationId]'s files: the configured number of
         * spaces, or two when unset or not a number between 1 and 8.
         */
        fun indent(project: Project, localizationId: String): String {
            val spaces = Settings.getInstance(project).config()
                .getLocalizationSetting(localizationId, INDENT)
                ?.trim()?.toIntOrNull()
                ?.takeIf { it in 1..MAX_INDENT }
                ?: DEFAULT_INDENT
            return " ".repeat(spaces)
        }
    }
}
