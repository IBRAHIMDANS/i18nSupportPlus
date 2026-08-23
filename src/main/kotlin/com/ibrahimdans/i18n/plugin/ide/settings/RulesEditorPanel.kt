package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import com.intellij.ui.dsl.builder.panel

/**
 * Key assistance rules editor: the rules on the left, one typed form on the right.
 *
 * It replaces a nine-column table of free text, where `priority` fell back to 0 on a typo
 * and `exclude` / `negated` silently read as false for anything but the exact word "false".
 * Here the two flags are checkboxes and the priority is a spinner, so neither can be
 * mistyped in the first place.
 */
internal class RulesEditorPanel(private val settings: Settings) : ItemEditorPanel<EditorRuleState>() {

    private val idField = boundTextField(PluginBundle.message("settings.rules.col.id"), 16) { rule, value ->
        rule.copy(id = value)
    }

    private val languageField = boundTextField(PluginBundle.message("settings.rules.col.language"), 12) { rule, value ->
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

    private val typeField = boundTextField(PluginBundle.message("settings.rules.col.type"), 16) { rule, value ->
        rule.copy(constraintType = value)
    }

    private val valueField = boundTextField(PluginBundle.message("settings.rules.col.value"), 24) { rule, value ->
        rule.copy(value = value)
    }

    private val matchModeField = boundTextField(PluginBundle.message("settings.rules.col.matchMode"), 12) { rule, value ->
        rule.copy(matchMode = value)
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
        row(PluginBundle.message("settings.rules.col.language")) { cell(languageField) }
        row(PluginBundle.message("settings.rules.col.trigger")) { cell(triggerField) }
        row(PluginBundle.message("settings.rules.col.priority")) {
            cell(prioritySpinner).comment(PluginBundle.message("settings.rules.col.priority.comment"))
        }
        row(PluginBundle.message("settings.rules.col.type")) { cell(typeField) }
        row(PluginBundle.message("settings.rules.col.value")) { cell(valueField) }
        row(PluginBundle.message("settings.rules.col.matchMode")) { cell(matchModeField) }
        row { cell(excludeBox) }
        row { cell(negatedBox) }
    }

    private fun bind(rule: EditorRuleState?) = load(rule) { selected ->
        idField.text = selected?.id ?: ""
        languageField.text = selected?.language ?: ""
        triggerField.text = selected?.trigger ?: ""
        prioritySpinner.value = (selected?.priority ?: 0).coerceIn(0, MAX_PRIORITY)
        excludeBox.isSelected = selected?.exclude ?: false
        typeField.text = selected?.constraintType ?: ""
        valueField.text = selected?.value ?: ""
        matchModeField.text = selected?.matchMode ?: ""
        negatedBox.isSelected = selected?.negated ?: false
        setFormEnabled(selected != null)
    }

    private fun setFormEnabled(enabled: Boolean) {
        idField.isEnabled = enabled
        languageField.isEnabled = enabled
        triggerField.isEnabled = enabled
        prioritySpinner.isEnabled = enabled
        excludeBox.isEnabled = enabled
        typeField.isEnabled = enabled
        valueField.isEnabled = enabled
        matchModeField.isEnabled = enabled
        negatedBox.isEnabled = enabled
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
        /** Rules are ordered against each other, not scored: three digits are plenty. */
        const val MAX_PRIORITY = 999

        /** Width the editor asks for when the form inside it needs no more. */
        const val EDITOR_MIN_WIDTH = 700

        /** How much of the item list is visible before it scrolls. */
        const val EDITOR_HEIGHT = 240
    }
}
