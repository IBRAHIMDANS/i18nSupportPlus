package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/**
 * Dialog mode: create a new key or edit an existing one.
 */
enum class Mode { CREATE, EDIT }

/**
 * Dialog for editing or creating i18n translation values.
 * Shows a text area per localization source, pre-filled with the current value if it exists.
 *
 * In CREATE mode, a Namespace combo is displayed below the Key field.
 * Changing the namespace refreshes the source list to show only matching files.
 * In EDIT mode, behavior is unchanged.
 */
class TranslationDialog(
    project: Project,
    private val fullKey: FullKey,
    private val mode: Mode = Mode.EDIT
) : DialogWrapper(project) {

    private val viewModel = DialogViewModel(project)
    private val sources: Map<LocalizationSource, String?> = viewModel.loadTranslations(fullKey)
    private val textAreas: MutableMap<LocalizationSource, JBTextArea> = mutableMapOf()
    private var keyField: JBTextField? = null

    // CREATE-mode only widgets
    private var namespaceCombo: JComboBox<String>? = null
    private var sourcesPanel: JPanel? = null

    init {
        title = if (mode == Mode.CREATE) "Create Translation" else "Edit Translation"
        init()
        window?.minimumSize = Dimension(520, 300)
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel()
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)

        // Key field section
        val keyPanel = JPanel(BorderLayout(4, 0))
        keyPanel.border = BorderFactory.createEmptyBorder(4, 0, 8, 0)
        keyPanel.add(JLabel("Key:"), BorderLayout.WEST)
        val field = JBTextField(fullKey.source)
        field.isEditable = (mode == Mode.CREATE)
        keyField = field
        keyPanel.add(field, BorderLayout.CENTER)
        keyPanel.alignmentX = JPanel.LEFT_ALIGNMENT
        keyPanel.maximumSize = Dimension(Int.MAX_VALUE, keyPanel.preferredSize.height)
        mainPanel.add(keyPanel)

        if (mode == Mode.CREATE) {
            // Namespace combo section
            val namespaces = viewModel.loadNamespaces()
            val combo = JComboBox(namespaces.toTypedArray())
            combo.maximumSize = Dimension(200, combo.preferredSize.height)
            namespaceCombo = combo

            // "+" button to create a new namespace
            val addButton = JButton("+")
            addButton.toolTipText = "Add namespace"
            addButton.preferredSize = Dimension(addButton.preferredSize.width, combo.preferredSize.height)
            addButton.maximumSize = addButton.preferredSize
            addButton.addActionListener {
                val input = JOptionPane.showInputDialog(
                    addButton,
                    "Namespace name (letters, digits, hyphens):",
                    "Add Namespace",
                    JOptionPane.PLAIN_MESSAGE
                )?.trim()
                if (input == null || input.isBlank()) return@addActionListener
                if (!input.matches(Regex("[a-zA-Z0-9-]+"))) {
                    Messages.showErrorDialog(
                        "Invalid namespace name. Only letters, digits and hyphens are allowed.",
                        "Invalid Name"
                    )
                    return@addActionListener
                }
                viewModel.createNamespace(input)
                // Refresh combo with new namespaces and select the newly created one
                val updated = viewModel.loadNamespaces()
                combo.removeAllItems()
                updated.forEach { combo.addItem(it) }
                combo.selectedItem = input
                // Trigger source panel refresh
                refreshSourcesPanel(input)
            }

            val nsPanel = JPanel()
            nsPanel.layout = BoxLayout(nsPanel, BoxLayout.X_AXIS)
            nsPanel.border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
            nsPanel.add(JLabel("Namespace:"))
            nsPanel.add(Box.createHorizontalStrut(4))
            nsPanel.add(combo)
            nsPanel.add(Box.createHorizontalStrut(4))
            nsPanel.add(addButton)
            nsPanel.add(Box.createHorizontalGlue())
            nsPanel.alignmentX = JPanel.LEFT_ALIGNMENT
            mainPanel.add(nsPanel)

            // Dynamic sources panel
            val dynPanel = JPanel()
            dynPanel.layout = BoxLayout(dynPanel, BoxLayout.Y_AXIS)
            dynPanel.alignmentX = JPanel.LEFT_ALIGNMENT
            sourcesPanel = dynPanel
            mainPanel.add(dynPanel)

            // Populate initial sources based on first namespace
            val initialNs = namespaces.firstOrNull()
            if (initialNs != null) {
                refreshSourcesPanel(initialNs)
            }

            // Listen for namespace changes
            combo.addActionListener {
                val selected = combo.selectedItem as? String ?: return@addActionListener
                refreshSourcesPanel(selected)
            }
        } else {
            // EDIT mode: render sources as before
            buildSourceWidgets(sources, mainPanel)
        }

        // Wrap in a scroll pane if there are many sources
        return if (sources.size > 3) {
            val outerScroll = JBScrollPane(mainPanel)
            outerScroll.preferredSize = Dimension(500, 400)
            outerScroll
        } else {
            mainPanel
        }
    }

    /**
     * Rebuilds the sources panel for the given namespace (CREATE mode only).
     */
    private fun refreshSourcesPanel(namespace: String) {
        val panel = sourcesPanel ?: return
        panel.removeAll()
        textAreas.clear()

        val newSources = viewModel.loadSourcesForNamespace(namespace)
        buildSourceWidgets(newSources, panel)

        panel.revalidate()
        panel.repaint()
    }

    /**
     * Creates label + textarea widgets for each source and adds them to [container].
     * Also registers each textarea in [textAreas].
     */
    private fun buildSourceWidgets(sourcesMap: Map<LocalizationSource, String?>, container: JPanel) {
        sourcesMap.forEach { (source, currentValue) ->
            val sourcePanel = JPanel()
            sourcePanel.layout = BoxLayout(sourcePanel, BoxLayout.Y_AXIS)
            sourcePanel.border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
            sourcePanel.alignmentX = JPanel.LEFT_ALIGNMENT

            val label = JLabel(source.displayPath)
            label.alignmentX = JLabel.LEFT_ALIGNMENT
            sourcePanel.add(label)

            val textArea = JBTextArea(currentValue ?: "", 4, 40)
            textArea.lineWrap = true
            textArea.wrapStyleWord = true
            textAreas[source] = textArea

            val scrollPane = JBScrollPane(textArea)
            scrollPane.preferredSize = Dimension(480, 80)
            scrollPane.alignmentX = JBScrollPane.LEFT_ALIGNMENT
            sourcePanel.add(scrollPane)

            container.add(sourcePanel)
        }
    }

    override fun doOKAction() {
        val effectiveKey = if (mode == Mode.CREATE) {
            val keyText = keyField?.text?.trim().orEmpty()
            val selectedNs = namespaceCombo?.selectedItem as? String
            // Prepend namespace if available and not already present in keyText
            val fullKeyText = if (selectedNs != null && !keyText.contains(':')) {
                "$selectedNs:$keyText"
            } else {
                keyText
            }
            viewModel.parseKey(fullKeyText) ?: return
        } else {
            fullKey
        }
        textAreas.forEach { (source, textArea) ->
            val currentValue = sources[source]
            val newValue = textArea.text
            // Save only if there is a non-blank value different from the original
            if (newValue.isNotBlank() && newValue != currentValue) {
                viewModel.saveTranslation(source, effectiveKey, newValue)
            }
        }
        super.doOKAction()
    }

    override fun doValidate(): ValidationInfo? {
        if (mode == Mode.CREATE) {
            val key = keyField?.text?.trim()
            if (key.isNullOrBlank()) {
                return ValidationInfo("Key cannot be empty", keyField)
            }
        }
        val hasAnyValue = textAreas.values.any { it.text.isNotBlank() }
        if (!hasAnyValue) {
            return ValidationInfo("At least one translation value must be provided")
        }
        return null
    }

    override fun getPreferredFocusedComponent(): JComponent? =
        if (mode == Mode.CREATE) keyField else textAreas.values.firstOrNull()
}
