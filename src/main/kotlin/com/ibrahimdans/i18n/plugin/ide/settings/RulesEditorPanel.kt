package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import com.ibrahimdans.i18n.plugin.rules.KeyRules
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import com.intellij.ui.dsl.builder.panel

/**
 * Key assistance rules editor: the rules on the left, one typed form on the right.
 *
 * It replaces a nine-column table of free text, where `priority` fell back to 0 on a typo
 * and `exclude` / `negated` silently read as false for anything but the exact word "false".
 * Here the two flags are checkboxes and the priority is a spinner, so neither can be
 * mistyped in the first place.
 *
 * The language, the constraint and the match mode are lists of what the rules engine reads, for
 * the same reason: a free-text `filepath` or `regexp` was stored as written and made the rule
 * silently ignored. A regex that does not compile is flagged under the value.
 */
internal class RulesEditorPanel(private val settings: Settings) : ItemEditorPanel<EditorRuleState>() {

    private val idField = boundTextField(PluginBundle.message("settings.rules.col.id"), 16) { rule, value ->
        rule.copy(id = value)
    }

    private val languageCombo = boundCombo(PluginBundle.message("settings.rules.col.language"), ::languageLabel) { rule, value ->
        rule.copy(language = value)
    }

    private val triggerField = boundTextField(PluginBundle.message("settings.rules.col.trigger"), 16) { rule, value ->
        rule.copy(trigger = value)
    }

    private val prioritySpinner =
        boundSpinner(PluginBundle.message("settings.rules.col.priority"), 0, MAX_PRIORITY) { rule, value ->
            rule.copy(priority = value)
        }

    private val excludeBox = boundCheckBox(PluginBundle.message("settings.rules.col.exclude")) { rule, value ->
        rule.copy(exclude = value)
    }

    private val typeCombo = boundCombo(PluginBundle.message("settings.rules.col.type"), ::constraintLabel) { rule, value ->
        rule.copy(constraintType = value)
    }

    private val valueField = boundTextField(PluginBundle.message("settings.rules.col.value"), 24) { rule, value ->
        rule.copy(value = value)
    }

    private val matchModeCombo = boundCombo(PluginBundle.message("settings.rules.col.matchMode"), ::matchModeLabel) { rule, value ->
        rule.copy(matchMode = value)
    }

    private val valueProblem = JLabel().apply {
        name = PluginBundle.message("settings.rules.value.invalidRegex")
        foreground = JBColor.RED
        isVisible = false
    }

    private val negatedBox = boundCheckBox(PluginBundle.message("settings.rules.col.negated")) { rule, value ->
        rule.copy(negated = value)
    }

    init {
        editor = ListEditorPanel(
            items = settings.rules,
            detailForm = detailForm(),
            newItem = ::newRule,
            labelOf = ::label,
            listName = "rules.list",
            addLabel = PluginBundle.message("settings.rules.add"),
            addName = "rules.add",
            removeLabel = PluginBundle.message("settings.rules.remove"),
            removeName = "rules.remove",
            onSelectionChanged = ::bind
        )
        add(editor, BorderLayout.CENTER)

        bind(null)
        editor.selectFirst()

        // The height is a deliberate choice: how many rows of the list to show before it
        // scrolls. The width is not ours to pick — it is whatever the detail form inside needs.
        // Pinning it narrower does not scroll the overflow, it cuts it off the right edge, which
        // is how the field comments reached the screen mid-sentence. Measured last, after the
        // form has been populated, for the same reason as in the modules editor.
        preferredSize = Dimension(maxOf(EDITOR_MIN_WIDTH, editor.preferredSize.width), EDITOR_HEIGHT)
    }

    private fun detailForm(): JPanel = panel {
        row(PluginBundle.message("settings.rules.col.id")) { cell(idField) }
        row(PluginBundle.message("settings.rules.col.language")) { cell(languageCombo) }
        row(PluginBundle.message("settings.rules.col.trigger")) { cell(triggerField) }
        row(PluginBundle.message("settings.rules.col.priority")) {
            cell(prioritySpinner).comment(PluginBundle.message("settings.rules.col.priority.comment"))
        }
        row(PluginBundle.message("settings.rules.col.type")) { cell(typeCombo) }
        row(PluginBundle.message("settings.rules.col.value")) { cell(valueField) }
        row("") { cell(valueProblem) }
        row(PluginBundle.message("settings.rules.col.matchMode")) { cell(matchModeCombo) }
        row { cell(excludeBox) }
        row { cell(negatedBox) }
    }

    private fun bind(rule: EditorRuleState?) = load(rule) { selected ->
        idField.text = selected?.id ?: ""
        fill(languageCombo, LANGUAGES, selected?.language ?: "")
        triggerField.text = selected?.trigger ?: ""
        prioritySpinner.value = (selected?.priority ?: 0).coerceIn(0, MAX_PRIORITY)
        excludeBox.isSelected = selected?.exclude ?: false
        fill(typeCombo, KeyRules.Constraint.ALL, selected?.constraintType ?: "")
        valueField.text = selected?.value ?: ""
        fill(matchModeCombo, KeyRules.MatchMode.ALL, selected?.matchMode?.ifBlank { KeyRules.MatchMode.EXACT } ?: KeyRules.MatchMode.EXACT)
        negatedBox.isSelected = selected?.negated ?: false
        setFormEnabled(selected != null)
    }

    private fun setFormEnabled(enabled: Boolean) {
        idField.isEnabled = enabled
        languageCombo.isEnabled = enabled
        triggerField.isEnabled = enabled
        prioritySpinner.isEnabled = enabled
        excludeBox.isEnabled = enabled
        typeCombo.isEnabled = enabled
        valueField.isEnabled = enabled
        matchModeCombo.isEnabled = enabled
        negatedBox.isEnabled = enabled
    }

    override fun onItemChanged() {
        val rule = editor.selected()
        valueProblem.isVisible = rule != null && rule.matchMode == KeyRules.MatchMode.REGEX &&
            rule.constraintType != KeyRules.Constraint.NONE && runCatching { Regex(rule.value) }.isFailure
        valueProblem.text = if (valueProblem.isVisible) PluginBundle.message("settings.rules.value.invalidRegex") else ""
    }

    private fun boundCombo(label: String, labelOf: (String) -> String, apply: (EditorRuleState, String) -> EditorRuleState): JComboBox<String> {
        val combo = JComboBox<String>()
        combo.name = label
        combo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                (component as JLabel).text = labelOf(value as? String ?: "")
                return component
            }
        }
        combo.addActionListener { mutate { rule -> apply(rule, combo.selectedItem as? String ?: "") } }
        return combo
    }

    /** The known values, plus the stored one when it comes from a hand-written or newer config, selected. */
    private fun fill(combo: JComboBox<String>, known: List<String>, current: String) {
        val items = if (current in known) known else known + current
        combo.model = DefaultComboBoxModel(items.toTypedArray())
        combo.selectedItem = current
    }

    private fun languageLabel(language: String): String =
        if (language.isBlank()) PluginBundle.message("settings.rules.language.all") else language

    private fun constraintLabel(type: String): String = when (type) {
        KeyRules.Constraint.NONE -> PluginBundle.message("settings.rules.type.none")
        KeyRules.Constraint.FILE_PATH -> PluginBundle.message("settings.rules.type.filePath")
        KeyRules.Constraint.IMPORT -> PluginBundle.message("settings.rules.type.import")
        KeyRules.Constraint.KEY_PATTERN -> PluginBundle.message("settings.rules.type.keyPattern")
        else -> type
    }

    private fun matchModeLabel(mode: String): String = when (mode) {
        KeyRules.MatchMode.EXACT -> PluginBundle.message("settings.rules.mode.exact")
        KeyRules.MatchMode.PREFIX -> PluginBundle.message("settings.rules.mode.prefix")
        KeyRules.MatchMode.REGEX -> PluginBundle.message("settings.rules.mode.regex")
        else -> mode
    }

    private fun label(rule: EditorRuleState): String =
        rule.id.ifBlank { PluginBundle.message("settings.rules.unnamed") }

    private fun newRule(): EditorRuleState {
        val taken = settings.rules.map { it.id }.toSet()
        var index = settings.rules.size + 1
        while (taken.contains(PluginBundle.message("settings.rules.default.id", index))) index++
        return EditorRuleState(id = PluginBundle.message("settings.rules.default.id", index))
    }

    private companion object {
        /** The language ids the integrations ask the rules about; empty means every language. */
        val LANGUAGES = listOf("", "js", "php")

        /** Rules are ordered against each other, not scored: three digits are plenty. */
        const val MAX_PRIORITY = 999

        /** Width the editor asks for when the form inside it needs no more. */
        const val EDITOR_MIN_WIDTH = 700

        /** How much of the item list is visible before it scrolls. */
        const val EDITOR_HEIGHT = 240
    }
}
