package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.ide.settings.ItemEditorPanel
import com.ibrahimdans.i18n.plugin.ide.settings.ListEditorPanel
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The *Machine Translation* form: the opt-in, then the engines tried in order, one typed form each.
 *
 * It edits [settings], a detached copy the configurable applies, and collects the API keys typed in
 * [keys] — written to the password safe on *Apply* only. [test] translates a sample with the selected
 * engine and key; the configurable runs it off the EDT.
 */
internal class MachineTranslationPanel(
    private val settings: MachineTranslationSettings.Settings,
    private val storedKey: (String) -> String,
    private val test: (EngineConfig, String, (String) -> Unit) -> Unit
) : ItemEditorPanel<EngineState>() {

    /** API keys typed in this form, by engine id. */
    val keys = mutableMapOf<String, String>()

    val enabledBox = JCheckBox(PluginBundle.message("settings.translate.enabled"), settings.enabled).apply {
        name = PluginBundle.message("settings.translate.enabled")
        addItemListener { settings.enabled = isSelected; refresh() }
    }

    private val warning = JLabel(PluginBundle.message("settings.translate.warning")).apply { foreground = JBColor.ORANGE }

    private val localNote = JLabel(PluginBundle.message("settings.translate.local")).apply { foreground = JBColor.GRAY; isVisible = false }

    private val presetCombo = JComboBox(DefaultComboBoxModel(TranslationEngines.presets.map { it.id }.toTypedArray())).apply {
        name = PluginBundle.message("settings.translate.preset")
        renderer = javax.swing.DefaultListCellRenderer().let { base ->
            javax.swing.ListCellRenderer { list, value, index, selected, focus ->
                base.getListCellRendererComponent(list, TranslationEngines.preset(value as String)?.name ?: value, index, selected, focus)
            }
        }
        addActionListener { applyPreset(selectedItem as? String) }
    }

    private val nameField = boundTextField(PluginBundle.message("settings.translate.name"), 28) { e, v -> e.copy(name = v) }
    private val urlField = boundTextField(PluginBundle.message("settings.translate.url"), 36) { e, v -> e.copy(url = v) }
    private val pathField = boundTextField(PluginBundle.message("settings.translate.responsePath"), 28) { e, v -> e.copy(responsePath = v) }
    private val headersArea = boundTextArea(PluginBundle.message("settings.translate.headers"), 3) { e, v -> e.copy(headers = v) }
    private val bodyArea = boundTextArea(PluginBundle.message("settings.translate.body"), 6) { e, v -> e.copy(body = v) }
    private val unescapeBox = boundCheckBox(PluginBundle.message("settings.translate.unescapeHtml")) { e, v -> e.copy(unescapeHtml = v) }

    val keyField = JPasswordField(28).apply {
        name = PluginBundle.message("settings.translate.apiKey")
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = keyTyped()
            override fun removeUpdate(e: DocumentEvent?) = keyTyped()
            override fun changedUpdate(e: DocumentEvent?) = keyTyped()
        })
    }

    private val testButton = JButton(PluginBundle.message("settings.translate.test")).apply {
        name = "translate.test"
        addActionListener { runTest() }
    }

    val testResult = JLabel(" ").apply { name = "translate.test.result" }

    init {
        editor = ListEditorPanel(
            items = settings.engines,
            detailForm = detailForm(),
            newItem = { EngineState.of(TranslationEngines.presets.first()) },
            labelOf = { it.name.ifBlank { it.url } },
            listName = "translate.engines",
            addLabel = PluginBundle.message("settings.translate.add"),
            addName = "translate.add",
            removeLabel = PluginBundle.message("settings.translate.remove"),
            removeName = "translate.remove",
            onSelectionChanged = ::bind
        )
        val top = JPanel(BorderLayout()).apply {
            add(enabledBox, BorderLayout.NORTH)
            add(warning, BorderLayout.CENTER)
        }
        add(top, BorderLayout.NORTH)
        add(editor, BorderLayout.CENTER)
        bind(null)
        editor.selectFirst()
        refresh()
    }

    private fun detailForm(): JPanel = panel {
        row(PluginBundle.message("settings.translate.preset")) { cell(presetCombo) }
        row(PluginBundle.message("settings.translate.name")) { cell(nameField) }
        row(PluginBundle.message("settings.translate.url")) { cell(urlField) }
        row("") { cell(localNote) }
        row(PluginBundle.message("settings.translate.apiKey")) { cell(keyField) }
        row(PluginBundle.message("settings.translate.headers")) { cell(headersArea).align(AlignX.FILL) }
        row(PluginBundle.message("settings.translate.body")) {
            cell(bodyArea).align(AlignX.FILL).comment(PluginBundle.message("settings.translate.body.comment"))
        }
        row(PluginBundle.message("settings.translate.responsePath")) { cell(pathField) }
        row("") { cell(unescapeBox) }
        row("") { cell(testButton); cell(testResult) }
    }

    private fun boundTextArea(label: String, rows: Int, apply: (EngineState, String) -> EngineState) =
        JTextArea(rows, 46).apply {
            name = label
            lineWrap = true
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = mutate { apply(it, text) }
                override fun removeUpdate(e: DocumentEvent?) = mutate { apply(it, text) }
                override fun changedUpdate(e: DocumentEvent?) = mutate { apply(it, text) }
            })
        }

    /** Fills the selected engine from [presetId]; the form is written, so the engine follows. */
    private fun applyPreset(presetId: String?) {
        if (loading) return
        val selected = editor.selected() ?: return
        val preset = presetId?.let(TranslationEngines::preset) ?: return
        if (selected.preset == preset.id) return
        val filled = EngineState.of(preset).copy(id = selected.id)
        mutate { filled }
        bind(filled)
    }

    private fun keyTyped() {
        if (loading) return
        editor.selected()?.let { keys[it.id] = String(keyField.password) }
    }

    private fun bind(engine: EngineState?) = load(engine) { selected ->
        presetCombo.selectedItem = selected?.preset
        nameField.text = selected?.name.orEmpty()
        urlField.text = selected?.url.orEmpty()
        headersArea.text = selected?.headers.orEmpty()
        bodyArea.text = selected?.body.orEmpty()
        pathField.text = selected?.responsePath.orEmpty()
        unescapeBox.isSelected = selected?.unescapeHtml == true
        keyField.text = selected?.let { keys[it.id] ?: storedKey(it.id) }.orEmpty()
        testResult.text = " "
        localNote.isVisible = selected?.local == true
        listOf(presetCombo, nameField, urlField, headersArea, bodyArea, pathField, unescapeBox, keyField, testButton)
            .forEach { it.isEnabled = selected != null }
    }

    private fun runTest() {
        val engine = editor.selected() ?: return
        testResult.foreground = JBColor.foreground()
        testResult.text = PluginBundle.message("settings.translate.test.running")
        test(engine.toConfig(), String(keyField.password)) { message -> testResult.text = message }
    }

    private fun refresh() {
        warning.isVisible = enabledBox.isSelected
        editor.isEnabled = enabledBox.isSelected
    }

    override fun onItemChanged() {
        localNote.isVisible = editor.selected()?.local == true
    }
}
