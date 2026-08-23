package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent
import kotlin.reflect.KMutableProperty0

private fun addLimitationsAndHandlers(component: JTextComponent, maxLength: Int, onChange: (newText: String) -> Unit = {}, isValid: (e: Char) -> Boolean = { true }) {
    component.addKeyListener(object: KeyAdapter() {
        override fun keyTyped(e: KeyEvent) {
            if (component.text.length - (if (component.selectedText==null) 0 else component.selectedText.length) >= maxLength || !isValid(e.keyChar)) {
                e.consume()
            }
        }
    })
    component.getDocument().addDocumentListener(object : DocumentListener {
        override fun changedUpdate(e: DocumentEvent?) = onChange(component.text)
        override fun insertUpdate(e: DocumentEvent?) = onChange(component.text)
        override fun removeUpdate(e: DocumentEvent?) = onChange(component.text)
    })
}

/**
 * Settings configuration panel.
 *
 * Layout is built with the Kotlin UI DSL, but the components keep two contracts
 * the UI tests rely on: every control's `name` is its bundle label, and every
 * edit writes to [Settings] immediately (Configurable diffs against a snapshot).
 *
 * Modules and rules are edited by [ModulesEditorPanel] and [RulesEditorPanel], which hold
 * the same contracts. Everything here stays plain Swing on purpose: the settings UI test
 * builds this panel in a bare JFrame, with no running Application behind it.
 */
class SettingsPanel(val settings: Settings, val project: Project) {

    private val diagnosticsPanel = ConfigDiagnosticsPanel(project)

    /**
     * Refreshes the panel state from the current settings (e.g. after a reset).
     * Also triggers diagnostic checks so the warning banner stays up to date.
     */
    fun reset() {
        diagnosticsPanel.refresh(settings.config())
    }

    /**
     * Returns Settings main panel
     */
    fun getRootPanel(): JPanel {
        diagnosticsPanel.refresh(settings.config())
        return panel {
            row { cell(diagnosticsPanel).align(AlignX.FILL) }

            group(PluginBundle.message("settings.group.namespaces")) {
                row(PluginBundle.message("settings.namespace.separator")) {
                    cell(separatorField(PluginBundle.message("settings.namespace.separator"), settings::nsSeparator))
                }
                row(PluginBundle.message("settings.key.separator")) {
                    cell(separatorField(PluginBundle.message("settings.key.separator"), settings::keySeparator))
                }
                row {
                    cell(checkbox(PluginBundle.message("settings.flat.keys"), settings::flatKeys))
                        .comment(PluginBundle.message("settings.flat.keys.comment"))
                }
                row(PluginBundle.message("settings.plural.separator")) {
                    cell(separatorField(PluginBundle.message("settings.plural.separator"), settings::pluralSeparator))
                }
                row(PluginBundle.message("settings.default.namespace")) {
                    cell(textField(PluginBundle.message("settings.default.namespace"), settings::defaultNs, maxLength = 1000, columns = 20))
                        .comment(PluginBundle.message("settings.default.namespace.comment"))
                }
            }

            group(PluginBundle.message("settings.group.scope")) {
                row {
                    cell(checkbox(PluginBundle.message("settings.search.in.project.files.only"), settings::searchInProjectOnly))
                }
                row(PluginBundle.message("settings.translations.root")) {
                    cell(textField(PluginBundle.message("settings.translations.root"), settings::translationsRoot, columns = 25))
                        .comment(PluginBundle.message("settings.translations.root.comment"))
                }
                row(PluginBundle.message("settings.excluded.directories")) {
                    cell(textField(PluginBundle.message("settings.excluded.directories"), settings::excludedDirectories, columns = 25))
                        .comment(PluginBundle.message("settings.excluded.directories.comment"))
                }
                row(PluginBundle.message("settings.excluded.file.extensions")) {
                    cell(textField(PluginBundle.message("settings.excluded.file.extensions"), settings::excludedFileExtensions, columns = 25))
                        .comment(PluginBundle.message("settings.excluded.file.extensions.comment"))
                }
            }

            group(PluginBundle.message("settings.group.folding")) {
                row {
                    cell(checkbox(PluginBundle.message("settings.folding.enabled"), settings::foldingEnabled))
                }
                row(PluginBundle.message("settings.folding.preferredLanguage")) {
                    cell(textField(PluginBundle.message("settings.folding.preferredLanguage"), settings::foldingPreferredLanguage, columns = 6))
                }
                row(PluginBundle.message("settings.folding.maxLength")) {
                    cell(numberField(PluginBundle.message("settings.folding.maxLength"), settings::foldingMaxLength))
                }
                row(PluginBundle.message("settings.preview.locale")) {
                    cell(textField(PluginBundle.message("settings.preview.locale"), settings::previewLocale, columns = 6))
                }
            }

            group(PluginBundle.message("settings.group.extraction")) {
                row {
                    cell(checkbox(PluginBundle.message("settings.extraction.sorted"), settings::extractSorted))
                }
                row {
                    cell(checkbox(PluginBundle.message("settings.sort.keys.alphabetically"), settings::sortKeysAlphabetically))
                }
            }

            group(PluginBundle.message("settings.group.gettext")) {
                row {
                    cell(checkbox(PluginBundle.message("settings.gettext.enabled"), settings::gettext))
                }
                row(PluginBundle.message("settings.gettext.aliases")) {
                    cell(textField(PluginBundle.message("settings.gettext.aliases"), settings::gettextAliases, columns = 20))
                }
            }

            group(PluginBundle.message("settings.group.inspections")) {
                row {
                    cell(checkbox(PluginBundle.message("settings.annotations.partially.translated.enabled"), settings::partialTranslationInspectionEnabled))
                }
            }

            group(PluginBundle.message("settings.group.appearance")) {
                row {
                    cell(checkbox(PluginBundle.message("settings.gutter.icons.enabled"), settings::gutterIconsEnabled))
                }
                row {
                    cell(checkbox(PluginBundle.message("settings.setup.wizard.enabled"), settings::setupWizardEnabled))
                }
            }

            group(PluginBundle.message("settings.modules.label")) {
                row {
                    cell(ModulesEditorPanel(settings, project)).align(Align.FILL)
                }.resizableRow()
            }

            group(PluginBundle.message("settings.rules.label")) {
                row {
                    cell(RulesEditorPanel(settings)).align(Align.FILL)
                }.resizableRow()
            }
        }
    }

    private fun checkbox(label: String, property: KMutableProperty0<Boolean>): JCheckBox {
        val checkbox = JCheckBox(label, property.get())
        checkbox.name = label
        checkbox.addItemListener { _ -> property.set(checkbox.isSelected) }
        return checkbox
    }

    private fun separatorField(label: String, property: KMutableProperty0<String>): JTextField {
        val control = JTextField(property.get(), 2)
        control.name = label
        addLimitationsAndHandlers(control, 1, property::set, {!" {}$`".contains(it)})
        return control
    }

    private fun textField(label: String, property: KMutableProperty0<String>, maxLength: Int = 100, columns: Int = 10): JTextField {
        val control = JTextField(property.get(), columns)
        control.name = label
        addLimitationsAndHandlers(control, maxLength, property::set)
        return control
    }

    private fun numberField(label: String, property: KMutableProperty0<Int>): JTextField {
        val control = JTextField(property.get().toString(), 4)
        control.name = label
        addLimitationsAndHandlers(control, 2, { if (it.isNotBlank()) property.set(it.toInt()) }, {('0'..'9').contains(it)})
        return control
    }
}
