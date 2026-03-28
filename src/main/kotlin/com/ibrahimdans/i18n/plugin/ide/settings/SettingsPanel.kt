package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.project.Project
import com.jgoodies.forms.factories.DefaultComponentFactory
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder
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
 * Settings configuration panel
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
        val root = JPanel()
        root.layout = BorderLayout()
        root.add(DefaultComponentFactory.getInstance().createSeparator("Settings"), BorderLayout.NORTH)
        root.add(settingsPanel(), BorderLayout.WEST)
        return root
    }

    private fun checkbox(label:String, property: KMutableProperty0<Boolean>): JPanel {
        val panel = JPanel()
        panel.preferredSize = Dimension(350, 30)
        panel.layout = BorderLayout()
        val checkbox = JCheckBox(label, property.get())
        checkbox.name = label
        checkbox.addItemListener { _ -> property.set(checkbox.isSelected) }
        panel.add(checkbox, BorderLayout.WEST)
        return panel
    }

    private fun separator(label:String, property: KMutableProperty0<String>):JPanel {
        val panel = JPanel()
        panel.layout = BorderLayout()
        panel.preferredSize = Dimension(350, 30)
        panel.add(JLabel(label), BorderLayout.WEST)
        val control = JTextField(property.get())
        control.name = label
        addLimitationsAndHandlers(control, 1, property::set, {!" {}$`".contains(it)})
        control.preferredSize = Dimension(30, 30)
        panel.add(control, BorderLayout.EAST)
        return panel
    }

    private fun textInput(label:String, property: KMutableProperty0<String>):JPanel {
        val panel = JPanel()
        panel.layout = BorderLayout()
        panel.preferredSize = Dimension(350, 30)
        panel.add(JLabel(label), BorderLayout.WEST)
        val control = JTextField(property.get())
        control.name = label
        addLimitationsAndHandlers(control, 100, property::set)
        control.preferredSize = Dimension(100, 30)
        panel.add(control, BorderLayout.EAST)
        return panel
    }

    private fun textArea(label:String, property: KMutableProperty0<String>):JPanel {
        val panel = JPanel()
        panel.layout = BorderLayout()
        val labelPanel = JPanel()
        labelPanel.layout = BorderLayout()
        labelPanel.add(JLabel(label), BorderLayout.PAGE_START)
        panel.add(labelPanel, BorderLayout.WEST)
        val control = JTextArea(property.get())
        control.name = label
        addLimitationsAndHandlers(control, 1000, property::set)
        control.lineWrap = true
        control.wrapStyleWord = true
        control.isEditable = true
        control.columns = 20
        control.setBorder(CompoundBorder(LineBorder(Color.LIGHT_GRAY), EmptyBorder(1, 3, 1, 1)))
        panel.add(control, BorderLayout.EAST)
        return panel
    }

    private fun numberInput(label:String, property: KMutableProperty0<Int>):JPanel {
        val panel = JPanel()
        panel.layout = BorderLayout()
        panel.preferredSize = Dimension(350, 30)
        panel.add(JLabel(label), BorderLayout.WEST)
        val control = JTextField(property.get().toString())
        control.name = label
        addLimitationsAndHandlers(control, 2, { if (it.isNotBlank()) property.set(it.toInt()) }, {('0'..'9').contains(it)})
        panel.add(control, BorderLayout.EAST)
        control.preferredSize = Dimension(100, 30)
        return panel
    }

    private fun gettext(): JPanel {
        val panel = JPanel()
        panel.layout = BorderLayout()
        panel.preferredSize = Dimension(350, 30)
        val gettextMode = JCheckBox(PluginBundle.getMessage("settings.gettext.enabled"), settings.gettext)
        gettextMode.addItemListener { _ -> settings.gettext = gettextMode.isSelected}
        panel.add(gettextMode, BorderLayout.WEST)
        return panel
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
        val table = JTable(model)
        table.name = "modules.table"
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.fillsViewportHeight = true

        val addButton = JButton(PluginBundle.getMessage("settings.modules.add"))
        addButton.name = "modules.add"
        addButton.addActionListener { model.addRow(arrayOf("", "", "", "", "", "")) }

        val removeButton = JButton(PluginBundle.getMessage("settings.modules.remove"))
        removeButton.name = "modules.remove"
        removeButton.addActionListener {
            val selectedRow = table.selectedRow
            if (selectedRow >= 0) model.removeRow(selectedRow)
        }

        val buttonPanel = JPanel()
        buttonPanel.layout = BoxLayout(buttonPanel, BoxLayout.X_AXIS)
        buttonPanel.add(addButton)
        buttonPanel.add(removeButton)

        val panel = JPanel()
        panel.layout = BorderLayout()
        panel.add(JLabel(PluginBundle.getMessage("settings.modules.label")), BorderLayout.NORTH)
        panel.add(JScrollPane(table), BorderLayout.CENTER)
        panel.add(buttonPanel, BorderLayout.SOUTH)
        panel.preferredSize = Dimension(700, 200)
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

        val addButton = JButton(PluginBundle.getMessage("settings.rules.add"))
        addButton.name = "rules.add"
        addButton.addActionListener { model.addRow(arrayOf("", "", "", "0", "false", "", "", "", "false")) }

        val removeButton = JButton(PluginBundle.getMessage("settings.rules.remove"))
        removeButton.name = "rules.remove"
        removeButton.addActionListener {
            val selectedRow = table.selectedRow
            if (selectedRow >= 0) model.removeRow(selectedRow)
        }

        val buttonPanel = JPanel()
        buttonPanel.layout = BoxLayout(buttonPanel, BoxLayout.X_AXIS)
        buttonPanel.add(addButton)
        buttonPanel.add(removeButton)

        val panel = JPanel()
        panel.layout = BorderLayout()
        panel.add(JLabel(PluginBundle.getMessage("settings.rules.label")), BorderLayout.NORTH)
        panel.add(JScrollPane(table), BorderLayout.CENTER)
        panel.add(buttonPanel, BorderLayout.SOUTH)
        panel.preferredSize = Dimension(700, 200)
        return panel
    }

    private fun settingsPanel(): JPanel {
        val root = JPanel()
        val panel = JPanel()
        root.layout = BorderLayout()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.add(checkbox(PluginBundle.getMessage("settings.search.in.project.files.only"), settings::searchInProjectOnly))
        panel.add(separator(PluginBundle.getMessage("settings.namespace.separator"), settings::nsSeparator))
        panel.add(separator(PluginBundle.getMessage("settings.key.separator"), settings::keySeparator))
        panel.add(separator(PluginBundle.getMessage("settings.plural.separator"), settings::pluralSeparator))
        panel.add(textArea(PluginBundle.getMessage("settings.default.namespace"), settings::defaultNs))
        panel.add(checkbox(PluginBundle.getMessage("settings.folding.enabled"), settings::foldingEnabled))
        panel.add(textInput(PluginBundle.getMessage("settings.folding.preferredLanguage"), settings::foldingPreferredLanguage))
        panel.add(numberInput(PluginBundle.getMessage("settings.folding.maxLength"), settings::foldingMaxLength))
        panel.add(checkbox(PluginBundle.getMessage("settings.extraction.sorted"), settings::extractSorted))
        panel.add(checkbox(PluginBundle.getMessage("settings.annotations.partially.translated.enabled"), settings::partialTranslationInspectionEnabled))
        panel.add(checkbox(PluginBundle.getMessage("settings.gettext.enabled"), settings::gettext))
        panel.add(textInput(PluginBundle.getMessage("settings.gettext.aliases"), settings::gettextAliases))
        panel.add(checkbox(PluginBundle.getMessage("settings.sort.keys.alphabetically"), settings::sortKeysAlphabetically))
        panel.add(textInput(PluginBundle.getMessage("settings.preview.locale"), settings::previewLocale))
        panel.add(textInput(PluginBundle.getMessage("settings.translations.root"), settings::translationsRoot))
        panel.add(checkbox(PluginBundle.getMessage("settings.gutter.icons.enabled"), settings::gutterIconsEnabled))
        panel.add(modulesTable())
        panel.add(rulesTable())

        diagnosticsPanel.refresh(settings.config())
        panel.add(diagnosticsPanel)
        root.add(panel, BorderLayout.PAGE_START)
        return root
    }
}
