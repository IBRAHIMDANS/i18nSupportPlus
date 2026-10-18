package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import com.ibrahimdans.i18n.plugin.ide.settings.rules.FrameworkImport
import com.ibrahimdans.i18n.plugin.ide.settings.rules.I18nAllyFrameworkImport
import com.ibrahimdans.i18n.plugin.ide.settings.rules.NotImportedReason
import com.ibrahimdans.i18n.plugin.rules.KeyRules
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
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
 *
 * *Import from i18n Ally…* takes over a project's `.vscode/i18n-ally-custom-framework.yml`: it
 * shows what [I18nAllyFrameworkImport] makes of it and adds the rules only once confirmed.
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

    private val importButton = JButton(PluginBundle.message("settings.rules.import.i18nAlly")).apply {
        name = "rules.import.i18nAlly"
        addActionListener { importI18nAllyFramework() }
    }

    init {
        editor = newEditor()
        add(editor, BorderLayout.CENTER)
        val importRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, IMPORT_ROW_GAP)).apply { add(importButton) }
        add(importRow, BorderLayout.SOUTH)

        bind(null)
        editor.selectFirst()

        // The height is a deliberate choice: how many rows of the list to show before it
        // scrolls. The width is not ours to pick — it is whatever the detail form inside needs.
        // Pinning it narrower does not scroll the overflow, it cuts it off the right edge, which
        // is how the field comments reached the screen mid-sentence. Measured last, after the
        // form has been populated, for the same reason as in the modules editor. The import row
        // comes on top of that height, so that it does not take it from the list.
        preferredSize = Dimension(maxOf(EDITOR_MIN_WIDTH, editor.preferredSize.width), EDITOR_HEIGHT + importRow.preferredSize.height)
    }

    private fun newEditor() = ListEditorPanel(
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

    /**
     * Reads the project's i18n Ally framework — or the file the user picks when there is none —
     * shows the report and adds the rules once confirmed. They reach the settings on *Apply*, as
     * any other edit of the list.
     */
    private fun importI18nAllyFramework() {
        val project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(importButton))
        val title = PluginBundle.message("settings.rules.import.title")
        val projectDir = project?.guessProjectDir()
        val file = projectDir?.findFileByRelativePath(I18nAllyFrameworkImport.CONFIG_PATH)
            ?: FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                    .withFileFilter { it.extension?.lowercase() in YAML_EXTENSIONS }
                    .withTitle(PluginBundle.message("settings.rules.import.chooser", I18nAllyFrameworkImport.CONFIG_PATH)),
                project,
                projectDir
            )
            ?: return
        val text = runCatching { VfsUtilCore.loadText(file) }.getOrDefault("")
        val plan = when (val result = I18nAllyFrameworkImport.plan(text, settings.rules)) {
            is FrameworkImport.Invalid -> {
                val message = if (result.line > 0) PluginBundle.message("settings.rules.import.invalid", file.presentableUrl, result.line)
                    else PluginBundle.message("settings.rules.import.empty", file.presentableUrl)
                Messages.showErrorDialog(project, message, title)
                return
            }
            is FrameworkImport.Plan -> result
        }
        if (plan.rules.isEmpty()) {
            Messages.showInfoMessage(project, report(plan), title)
            return
        }
        val confirmed = Messages.showOkCancelDialog(
            project,
            report(plan),
            title,
            PluginBundle.message("settings.rules.import.confirm"),
            Messages.getCancelButton(),
            Messages.getQuestionIcon()
        ) == Messages.OK
        if (!confirmed) return
        settings.rules.addAll(plan.rules)
        // The list editor shows the items it was built with: a new one picks up the imported rules.
        remove(editor)
        editor = newEditor()
        add(editor, BorderLayout.CENTER)
        bind(null)
        editor.selectFirst()
        revalidate()
        repaint()
    }

    private fun report(plan: FrameworkImport.Plan): String = buildList {
        add(PluginBundle.message("settings.rules.import.rules", plan.rules.size))
        plan.rules.forEach { add("  ${it.trigger} (${it.language})") }
        if (plan.callTemplates.isNotEmpty()) {
            add("")
            add(PluginBundle.message("settings.rules.import.templates"))
            plan.callTemplates.forEach { add("  $it") }
        }
        if (plan.notImported.isNotEmpty()) {
            add("")
            add(PluginBundle.message("settings.rules.import.skipped"))
            plan.notImported.forEach { add("  ${it.entry} — ${reasonLabel(it.reason)}") }
        }
    }.joinToString("\n")

    private fun reasonLabel(reason: NotImportedReason): String = when (reason) {
        NotImportedReason.UNSUPPORTED_LANGUAGE -> PluginBundle.message("settings.rules.import.reason.language")
        NotImportedReason.NOT_A_FUNCTION_CALL -> PluginBundle.message("settings.rules.import.reason.regex")
        NotImportedReason.NO_SUPPORTED_LANGUAGE -> PluginBundle.message("settings.rules.import.reason.noLanguage")
        NotImportedReason.DUPLICATE_RULE -> PluginBundle.message("settings.rules.import.reason.duplicate")
        NotImportedReason.TEMPLATE_NOT_A_CALL -> PluginBundle.message("settings.rules.import.reason.template")
        NotImportedReason.NOT_APPLICABLE -> PluginBundle.message("settings.rules.import.reason.notApplicable")
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

        /** The extensions offered when the project has no i18n Ally framework where i18n Ally reads it. */
        val YAML_EXTENSIONS = setOf("yml", "yaml")

        /** Rules are ordered against each other, not scored: three digits are plenty. */
        const val MAX_PRIORITY = 999

        /** Width the editor asks for when the form inside it needs no more. */
        const val EDITOR_MIN_WIDTH = 700

        /** How much of the item list is visible before it scrolls. */
        const val EDITOR_HEIGHT = 240

        /** Gap above and below the import button, apart from the list buttons. */
        const val IMPORT_ROW_GAP = 4
    }
}
