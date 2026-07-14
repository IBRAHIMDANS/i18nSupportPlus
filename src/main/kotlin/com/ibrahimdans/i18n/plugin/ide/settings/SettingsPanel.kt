package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel
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

            group(PluginBundle.getMessage("settings.group.namespaces")) {
                row(PluginBundle.getMessage("settings.namespace.separator")) {
                    cell(separatorField(PluginBundle.getMessage("settings.namespace.separator"), settings::nsSeparator))
                }
                row(PluginBundle.getMessage("settings.key.separator")) {
                    cell(separatorField(PluginBundle.getMessage("settings.key.separator"), settings::keySeparator))
                }
                row(PluginBundle.getMessage("settings.plural.separator")) {
                    cell(separatorField(PluginBundle.getMessage("settings.plural.separator"), settings::pluralSeparator))
                }
                row(PluginBundle.getMessage("settings.default.namespace")) {
                    cell(textField(PluginBundle.getMessage("settings.default.namespace"), settings::defaultNs, maxLength = 1000, columns = 20))
                        .comment(PluginBundle.getMessage("settings.default.namespace.comment"))
                }
            }

            group(PluginBundle.getMessage("settings.group.scope")) {
                row {
                    cell(checkbox(PluginBundle.getMessage("settings.search.in.project.files.only"), settings::searchInProjectOnly))
                }
                row(PluginBundle.getMessage("settings.translations.root")) {
                    cell(textField(PluginBundle.getMessage("settings.translations.root"), settings::translationsRoot, columns = 25))
                        .comment(PluginBundle.getMessage("settings.translations.root.comment"))
                }
                row(PluginBundle.getMessage("settings.excluded.directories")) {
                    cell(textField(PluginBundle.getMessage("settings.excluded.directories"), settings::excludedDirectories, columns = 25))
                        .comment(PluginBundle.getMessage("settings.excluded.directories.comment"))
                }
                row(PluginBundle.getMessage("settings.excluded.file.extensions")) {
                    cell(textField(PluginBundle.getMessage("settings.excluded.file.extensions"), settings::excludedFileExtensions, columns = 25))
                        .comment(PluginBundle.getMessage("settings.excluded.file.extensions.comment"))
                }
            }

            group(PluginBundle.getMessage("settings.group.folding")) {
                row {
                    cell(checkbox(PluginBundle.getMessage("settings.folding.enabled"), settings::foldingEnabled))
                }
                row(PluginBundle.getMessage("settings.folding.preferredLanguage")) {
                    cell(textField(PluginBundle.getMessage("settings.folding.preferredLanguage"), settings::foldingPreferredLanguage, columns = 6))
                }
                row(PluginBundle.getMessage("settings.folding.maxLength")) {
                    cell(numberField(PluginBundle.getMessage("settings.folding.maxLength"), settings::foldingMaxLength))
                }
                row(PluginBundle.getMessage("settings.preview.locale")) {
                    cell(textField(PluginBundle.getMessage("settings.preview.locale"), settings::previewLocale, columns = 6))
                }
            }

            group(PluginBundle.getMessage("settings.group.extraction")) {
                row {
                    cell(checkbox(PluginBundle.getMessage("settings.extraction.sorted"), settings::extractSorted))
                }
                row {
                    cell(checkbox(PluginBundle.getMessage("settings.sort.keys.alphabetically"), settings::sortKeysAlphabetically))
                }
            }

            group(PluginBundle.getMessage("settings.group.gettext")) {
                row {
                    cell(checkbox(PluginBundle.getMessage("settings.gettext.enabled"), settings::gettext))
                }
                row(PluginBundle.getMessage("settings.gettext.aliases")) {
                    cell(textField(PluginBundle.getMessage("settings.gettext.aliases"), settings::gettextAliases, columns = 20))
                }
            }

            group(PluginBundle.getMessage("settings.group.inspections")) {
                row {
                    cell(checkbox(PluginBundle.getMessage("settings.annotations.partially.translated.enabled"), settings::partialTranslationInspectionEnabled))
                }
            }

            group(PluginBundle.getMessage("settings.group.appearance")) {
                row {
                    cell(checkbox(PluginBundle.getMessage("settings.gutter.icons.enabled"), settings::gutterIconsEnabled))
                }
                row {
                    cell(checkbox(PluginBundle.getMessage("settings.setup.wizard.enabled"), settings::setupWizardEnabled))
                }
            }

            group(PluginBundle.getMessage("settings.modules.label")) {
                row {
                    cell(modulesTable()).align(Align.FILL)
                }.resizableRow()
            }

            group(PluginBundle.getMessage("settings.rules.label")) {
                row {
                    cell(rulesTable()).align(Align.FILL)
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

    private fun modulesTable(): JPanel {
        val columnNames = arrayOf(
            PluginBundle.getMessage("settings.modules.name"),
            PluginBundle.getMessage("settings.modules.pathTemplate"),
            PluginBundle.getMessage("settings.modules.fileTemplate"),
            PluginBundle.getMessage("settings.modules.keyTemplate"),
            PluginBundle.getMessage("settings.modules.rootDirectory"),
            PluginBundle.getMessage("settings.modules.preset")
        )
        val model = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int) = true
        }
        settings.modules.forEach { m ->
            model.addRow(arrayOf(m.name, m.pathTemplate, m.fileTemplate, m.keyTemplate, m.rootDirectory, m.preset))
        }
        model.addTableModelListener {
            settings.modules.clear()
            for (row in 0 until model.rowCount) {
                settings.modules.add(ModuleConfig(
                    name          = model.getValueAt(row, 0) as? String ?: "",
                    pathTemplate  = model.getValueAt(row, 1) as? String ?: "",
                    fileTemplate  = model.getValueAt(row, 2) as? String ?: "",
                    keyTemplate   = model.getValueAt(row, 3) as? String ?: "",
                    rootDirectory = model.getValueAt(row, 4) as? String ?: "",
                    preset        = model.getValueAt(row, 5) as? String ?: ""
                ))
            }
        }
        // Plain JTable/JButton on purpose: JBTable and ToolbarDecorator require a
        // running Application, and this panel must stay buildable in plain Swing tests.
        val table = JTable(model)
        table.name = "modules.table"
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.fillsViewportHeight = true
        return tableWithButtons(
            table,
            addLabel = PluginBundle.getMessage("settings.modules.add"),
            addName = "modules.add",
            removeLabel = PluginBundle.getMessage("settings.modules.remove"),
            removeName = "modules.remove",
            onAdd = { model.addRow(arrayOf("", "", "", "", "", "")) },
            onRemove = { if (table.selectedRow >= 0) model.removeRow(table.selectedRow) }
        )
    }

    private fun tableWithButtons(
        table: JTable,
        addLabel: String,
        addName: String,
        removeLabel: String,
        removeName: String,
        onAdd: () -> Unit,
        onRemove: () -> Unit
    ): JPanel {
        val addButton = JButton(addLabel)
        addButton.name = addName
        addButton.addActionListener { onAdd() }

        val removeButton = JButton(removeLabel)
        removeButton.name = removeName
        removeButton.addActionListener { onRemove() }

        val buttonPanel = JPanel()
        buttonPanel.layout = BoxLayout(buttonPanel, BoxLayout.X_AXIS)
        buttonPanel.add(addButton)
        buttonPanel.add(removeButton)

        val panel = JPanel()
        panel.layout = BorderLayout()
        panel.add(JScrollPane(table), BorderLayout.CENTER)
        panel.add(buttonPanel, BorderLayout.SOUTH)
        panel.preferredSize = Dimension(700, 160)
        return panel
    }

    private fun rulesTable(): JPanel {
        val columnNames = arrayOf(
            PluginBundle.getMessage("settings.rules.col.id"),
            PluginBundle.getMessage("settings.rules.col.language"),
            PluginBundle.getMessage("settings.rules.col.trigger"),
            PluginBundle.getMessage("settings.rules.col.priority"),
            PluginBundle.getMessage("settings.rules.col.exclude"),
            PluginBundle.getMessage("settings.rules.col.type"),
            PluginBundle.getMessage("settings.rules.col.value"),
            PluginBundle.getMessage("settings.rules.col.matchMode"),
            PluginBundle.getMessage("settings.rules.col.negated")
        )
        val model = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int) = true
        }
        settings.rules.forEach { r ->
            model.addRow(arrayOf(r.id, r.language, r.trigger, r.priority.toString(), r.exclude.toString(), r.constraintType, r.value, r.matchMode, r.negated.toString()))
        }
        model.addTableModelListener {
            settings.rules.clear()
            for (row in 0 until model.rowCount) {
                settings.rules.add(EditorRuleState(
                    id             = model.getValueAt(row, 0) as? String ?: "",
                    language       = model.getValueAt(row, 1) as? String ?: "",
                    trigger        = model.getValueAt(row, 2) as? String ?: "",
                    priority       = (model.getValueAt(row, 3) as? String)?.toIntOrNull() ?: 0,
                    exclude        = (model.getValueAt(row, 4) as? String)?.toBooleanStrictOrNull() ?: false,
                    constraintType = model.getValueAt(row, 5) as? String ?: "",
                    value          = model.getValueAt(row, 6) as? String ?: "",
                    matchMode      = model.getValueAt(row, 7) as? String ?: "",
                    negated        = (model.getValueAt(row, 8) as? String)?.toBooleanStrictOrNull() ?: false
                ))
            }
        }
        val table = JTable(model)
        table.name = "rules.table"
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.fillsViewportHeight = true
        return tableWithButtons(
            table,
            addLabel = PluginBundle.getMessage("settings.rules.add"),
            addName = "rules.add",
            removeLabel = PluginBundle.getMessage("settings.rules.remove"),
            removeName = "rules.remove",
            onAdd = { model.addRow(arrayOf("", "", "", "0", "false", "", "", "", "false")) },
            onRemove = { if (table.selectedRow >= 0) model.removeRow(table.selectedRow) }
        )
    }
}
