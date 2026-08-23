package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.text.DefaultHighlighter

/**
 * Dialog mode: create a new key or edit an existing one.
 */
enum class Mode { CREATE, EDIT }

/**
 * Dialog for editing or creating i18n translation values.
 *
 * One row per localization source, titled by its **locale** — the one thing the user is looking
 * for — with the file path demoted to the row's caption. The path used to be the title, so a
 * dialog over four locales showed four paths and not a single language name.
 *
 * The key is checked while it is typed rather than at OK time: available, already taken (which
 * stays confirmable — correcting a value is what this dialog is for) or malformed. The namespace
 * the combo contributes is shown greyed next to the field, so what will be written is readable
 * without mentally concatenating it.
 *
 * Values are compared against each other for message variables (`{{count}}`, `{name}`, `%s`):
 * they are highlighted where they appear, and a locale that drops one the others carry says so
 * under its own field. See [DialogViewModel]'s companion object for those rules — they are pure
 * and tested there.
 *
 * In CREATE mode a Namespace combo is displayed below the Key field; changing it refreshes the
 * source list to show only matching files.
 */
class TranslationDialog(
    private val project: Project,
    private val fullKey: FullKey,
    private val mode: Mode = Mode.EDIT
) : DialogWrapper(project) {

    private val viewModel = DialogViewModel(project)
    private val sources: Map<LocalizationSource, String?> = viewModel.loadTranslations(fullKey)

    private val textAreas = LinkedHashMap<LocalizationSource, JBTextArea>()
    private val variableWarnings = LinkedHashMap<LocalizationSource, JBLabel>()
    private val variableWarningRows = LinkedHashMap<LocalizationSource, Row>()

    private val keyField = JBTextField(fullKey.source)
    private val keyStatusLabel = JBLabel()
    private val namespacePrefixLabel = JBLabel()

    /**
     * The namespace prefix and the key field as one control.
     *
     * They are held together by a [BorderLayout] rather than by two DSL cells so that the row
     * below — the live status — sits in the same grid column as the whole field and can use its
     * full width, instead of being clipped to the width of the prefix.
     */
    private val keyControl = JPanel(BorderLayout(JBUI.scale(PREFIX_GAP), 0)).apply {
        add(namespacePrefixLabel, BorderLayout.WEST)
        add(keyField, BorderLayout.CENTER)
    }

    /** Holds the per-locale rows, which are rebuilt whenever the namespace changes. */
    private val sourcesHost = JPanel(BorderLayout())

    private var namespaceCombo: JComboBox<String>? = null
    private var copyButton: JButton? = null

    /** Keys already defined under the selected namespace; refreshed together with the sources. */
    private var keysInNamespace: Set<String> = emptySet()

    private val nsSeparator: String = Settings.getInstance(project).config().nsSeparator

    /**
     * Empty when the project stores flat keys: there is then nothing to split, and a dot inside
     * a key is a character like any other rather than a level change.
     */
    private val keySeparator: String =
        Settings.getInstance(project).config().let { if (it.usesFlatKeys()) "" else it.keySeparator }

    init {
        title =
            if (mode == Mode.CREATE) PluginBundle.message("dialog.translation.title.create")
            else PluginBundle.message("dialog.translation.title.edit")
        init()
        window?.minimumSize = JBUI.size(MIN_WIDTH, MIN_HEIGHT)
    }

    override fun createCenterPanel(): JComponent {
        keyField.isEditable = (mode == Mode.CREATE)
        keyField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refreshKeyStatus()
        })
        namespacePrefixLabel.foreground = NamedColorUtil.getInactiveTextColor()

        val content = panel {
            // The prefix sits against the field rather than inside it: a text component renders
            // no greyed prefix of its own, and an icon extension cannot carry a namespace name.
            // Glued to the left of the field, it reads as one control.
            row(PluginBundle.message("dialog.translation.key.label")) {
                cell(keyControl).align(AlignX.FILL)
            }
            // Nothing to say about a key that cannot be edited.
            row("") {
                cell(keyStatusLabel).align(AlignX.FILL)
            }.visible(mode == Mode.CREATE)

            if (mode == Mode.CREATE) {
                row(PluginBundle.message("dialog.translation.namespace.label")) {
                    cell(namespaceComboBox()).gap(RightGap.SMALL)
                    cell(addNamespaceButton())
                }
            }

            row {
                cell(sourcesHost).align(Align.FILL)
            }.resizableRow()

            row {
                copyButton = button(PluginBundle.message("dialog.translation.copy.button")) {
                    copyToEmptyLocales()
                }
                    .comment(PluginBundle.message("dialog.translation.copy.comment"))
                    .component
            }
        }

        if (mode == Mode.CREATE) {
            val namespace = selectedNamespace()
            refreshSources(
                if (namespace == null) emptyMap() else viewModel.loadSourcesForNamespace(namespace),
                namespace
            )
        } else {
            refreshSources(sources, fullKey.ns?.text)
        }

        val scrollPane = JBScrollPane(content)
        scrollPane.border = JBUI.Borders.empty()
        scrollPane.preferredSize = JBUI.size(PREFERRED_WIDTH, PREFERRED_HEIGHT)
        return scrollPane
    }

    /**
     * The namespace combo, which owns the namespace the key will be written under.
     * A [JComboBox] built over a non-empty array selects its first item, so the initial
     * selection is the one the sources are first built for.
     */
    private fun namespaceComboBox(): JComboBox<String> {
        val combo = JComboBox(viewModel.loadNamespaces().toTypedArray())
        namespaceCombo = combo
        combo.addActionListener {
            val selected = combo.selectedItem as? String ?: return@addActionListener
            refreshSources(viewModel.loadSourcesForNamespace(selected), selected)
        }
        return combo
    }

    /**
     * The "+" button creating a namespace without leaving the dialog.
     */
    private fun addNamespaceButton(): JButton {
        val addButton = JButton("+")
        // Same wording as the tool window's own Add Namespace action: the two are the
        // one feature reached from two places, so they share the bundle keys rather than
        // drifting apart in a second copy.
        addButton.toolTipText = PluginBundle.message("toolwindow.action.add.namespace")
        addButton.addActionListener {
            // Parented on the button, not on the project: this prompt is opened from inside
            // a modal dialog, so it has to be anchored to that dialog's window. A
            // project-parented one is anchored to the IDE frame instead, which the modal
            // dialog sits above.
            val input = Messages.showInputDialog(
                addButton,
                PluginBundle.message("toolwindow.action.add.namespace.prompt"),
                PluginBundle.message("toolwindow.action.add.namespace"),
                null,
                null,
                namespaceValidator()
            )?.trim()
            if (input.isNullOrBlank()) return@addActionListener
            val combo = namespaceCombo ?: return@addActionListener
            viewModel.createNamespace(input)
            // Refresh combo with new namespaces and select the newly created one
            val updated = viewModel.loadNamespaces()
            combo.removeAllItems()
            updated.forEach { combo.addItem(it) }
            combo.selectedItem = input
            // Selecting an item the combo already held fires no event, so the refresh the
            // listener would have done is asked for explicitly.
            refreshSources(viewModel.loadSourcesForNamespace(input), input)
        }
        return addButton
    }

    /**
     * Rebuilds the per-locale rows for [namespace] and re-reads what that namespace already
     * defines, so the key check answers about the namespace actually selected.
     */
    private fun refreshSources(sourcesMap: Map<LocalizationSource, String?>, namespace: String?) {
        textAreas.clear()
        variableWarnings.clear()
        variableWarningRows.clear()
        // Only CREATE mode asks whether a key is free; EDIT mode is looking at one that exists,
        // so it never pays for the project-wide read behind existingKeys().
        keysInNamespace = if (mode == Mode.CREATE) viewModel.existingKeys(namespace) else emptySet()
        namespacePrefixLabel.text =
            if (mode == Mode.CREATE && !namespace.isNullOrBlank()) "$namespace$nsSeparator" else ""

        sourcesHost.removeAll()
        sourcesHost.add(buildSourceRows(sourcesMap), BorderLayout.CENTER)
        sourcesHost.revalidate()
        sourcesHost.repaint()

        refreshKeyStatus()
        refreshVariables()
    }

    /**
     * One row per source: the locale as the row's title, the path as its caption, and a hidden
     * warning row underneath that surfaces only when that locale drops a variable.
     */
    private fun buildSourceRows(sourcesMap: Map<LocalizationSource, String?>): JComponent = panel {
        sourcesMap.forEach { (source, currentValue) ->
            val textArea = JBTextArea(currentValue ?: "", TEXT_AREA_ROWS, TEXT_AREA_COLUMNS)
            textArea.lineWrap = true
            textArea.wrapStyleWord = true
            textArea.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = refreshVariables()
            })
            textAreas[source] = textArea

            val warning = JBLabel()
            warning.icon = AllIcons.General.Warning
            variableWarnings[source] = warning

            row(source.localeLabel()) {
                cell(JBScrollPane(textArea))
                    .align(AlignX.FILL)
                    .comment(source.displayPath)
            }
            variableWarningRows[source] = row("") {
                cell(warning).align(AlignX.FILL)
            }.visible(false)
        }
    }

    /**
     * The key as typed, without the namespace the combo contributes: the check and the set of
     * existing keys both speak in keys relative to their namespace.
     */
    private fun keyText(): String {
        val typed = keyField.text.orEmpty().trim()
        val namespace = selectedNamespace()
        return if (mode == Mode.CREATE && !namespace.isNullOrBlank()) {
            typed.removePrefix("$namespace$nsSeparator")
        } else {
            typed
        }
    }

    private fun selectedNamespace(): String? =
        if (mode == Mode.CREATE) namespaceCombo?.selectedItem as? String else fullKey.ns?.text

    /**
     * Says under the field what the typed key will do, while it is being typed. An empty field
     * says nothing: the dialog opens empty, and shouting at a user who has not typed yet is
     * noise — OK-time validation still refuses it.
     */
    private fun refreshKeyStatus() {
        if (mode != Mode.CREATE) {
            keyStatusLabel.text = ""
            keyStatusLabel.icon = null
            return
        }
        when (DialogViewModel.checkKey(keyText(), keySeparator, keysInNamespace)) {
            KeyCheck.EMPTY -> {
                keyStatusLabel.text = ""
                keyStatusLabel.icon = null
            }
            KeyCheck.INVALID_SEGMENT -> {
                keyStatusLabel.text = PluginBundle.message("dialog.translation.key.status.invalid")
                keyStatusLabel.icon = AllIcons.General.Error
            }
            KeyCheck.TAKEN -> {
                keyStatusLabel.text = PluginBundle.message("dialog.translation.key.status.taken")
                keyStatusLabel.icon = AllIcons.General.Warning
            }
            KeyCheck.AVAILABLE -> {
                keyStatusLabel.text = PluginBundle.message("dialog.translation.key.status.available")
                keyStatusLabel.icon = AllIcons.General.InspectionsOK
            }
        }
    }

    /**
     * Highlights the message variables in every value and flags the locales that drop one.
     * Runs on each keystroke: both are read-only passes over text already in memory.
     */
    private fun refreshVariables() {
        textAreas.values.forEach(::highlightVariables)

        val valuesByLocale = textAreas.entries.associate { (source, area) -> source.localeLabel() to area.text }
        val missing = DialogViewModel.missingVariables(valuesByLocale)
        textAreas.keys.forEach { source ->
            val lost = missing[source.localeLabel()].orEmpty()
            variableWarnings[source]?.text =
                if (lost.isEmpty()) ""
                else PluginBundle.message("dialog.translation.variables.missing", lost.sorted().joinToString(", "))
            variableWarningRows[source]?.visible(lost.isNotEmpty())
        }

        // Nothing to copy from, or nowhere to copy to: the button would be a no-op.
        copyButton?.isEnabled =
            textAreas.values.any { it.text.isNotBlank() } && textAreas.values.any { it.text.isBlank() }
    }

    private fun highlightVariables(textArea: JBTextArea) {
        textArea.highlighter?.let { highlighter ->
            highlighter.removeAllHighlights()
            val painter = DefaultHighlighter.DefaultHighlightPainter(VARIABLE_HIGHLIGHT)
            DialogViewModel.variableRanges(textArea.text).forEach { range ->
                // The document is read a hair after the ranges were computed; a range that no
                // longer fits is dropped rather than taking the dialog down with it.
                runCatching { highlighter.addHighlight(range.first, range.last + 1, painter) }
            }
        }
    }

    /**
     * Fills every locale left empty with the value of the donor locale: the one the module
     * declares as its reference, or the fullest one when it declares none — see
     * [DialogViewModel.localeToCopyFrom].
     */
    private fun copyToEmptyLocales() {
        val donorLocale = viewModel.localeToCopyFrom(textAreas.keys)
        val value = textAreas.entries
            .filter { it.value.text.isNotBlank() }
            .sortedByDescending { it.key.localeLabel() == donorLocale }
            .firstOrNull()
            ?.value
            ?.text
            ?: return
        textAreas.values.filter { it.text.isBlank() }.forEach { it.text = value }
    }

    override fun doOKAction() {
        val effectiveKey = if (mode == Mode.CREATE) {
            val typedKey = keyText()
            val selectedNs = selectedNamespace()
            // Prepend the namespace, unless the key already carries one of its own. The
            // separator comes from the settings rather than being a hard-coded ':', so a
            // project that separates namespaces differently is written the way it reads.
            val alreadyQualified = nsSeparator.isNotEmpty() && typedKey.contains(nsSeparator)
            val fullKeyText =
                if (selectedNs != null && nsSeparator.isNotEmpty() && !alreadyQualified) {
                    "$selectedNs$nsSeparator$typedKey"
                } else {
                    typedKey
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
            when (DialogViewModel.checkKey(keyText(), keySeparator, keysInNamespace)) {
                KeyCheck.EMPTY ->
                    return ValidationInfo(PluginBundle.message("dialog.translation.error.key.empty"), keyField)
                KeyCheck.INVALID_SEGMENT ->
                    return ValidationInfo(PluginBundle.message("dialog.translation.key.status.invalid"), keyField)
                // An existing key is not an error: correcting a translation is what this dialog
                // is for. The warning under the field is the whole of what is owed here.
                KeyCheck.TAKEN, KeyCheck.AVAILABLE -> Unit
            }
        }
        val hasAnyValue = textAreas.values.any { it.text.isNotBlank() }
        if (!hasAnyValue) {
            return ValidationInfo(PluginBundle.message("dialog.translation.error.value.required"))
        }
        return null
    }

    override fun getPreferredFocusedComponent(): JComponent =
        if (mode == Mode.CREATE) keyField else textAreas.values.firstOrNull() ?: keyField

    /**
     * Rejects an invalid namespace while the user types, so the input dialog cannot be
     * confirmed with a name we would have to reject afterwards in a second dialog.
     * The input is trimmed first, as the caller trims the accepted value too.
     */
    private fun namespaceValidator() = object : InputValidator {
        override fun checkInput(inputString: String?): Boolean = isValidNamespace(inputString)

        override fun canClose(inputString: String?): Boolean = isValidNamespace(inputString)
    }

    companion object {
        private val NAMESPACE_REGEX = Regex("[a-zA-Z0-9-]+")

        private const val TEXT_AREA_ROWS = 3
        private const val TEXT_AREA_COLUMNS = 40
        private const val PREFIX_GAP = 2

        // Sizes go through JBUI so a HiDPI screen scales them instead of truncating the dialog.
        private const val MIN_WIDTH = 520
        private const val MIN_HEIGHT = 300
        private const val PREFERRED_WIDTH = 620
        private const val PREFERRED_HEIGHT = 420

        /** Light enough to read black or white text through it, in either theme. */
        private val VARIABLE_HIGHLIGHT = JBColor(0xFFF2C4, 0x4C4426)

        /**
         * A namespace names a translation file, so it is restricted to letters, digits and
         * hyphens. The rule is named and reachable rather than inlined in the validator: the
         * validator is a widget listener and cannot be exercised headlessly, and this rule
         * used to live in an after-the-fact error dialog with nothing pinning it.
         */
        internal fun isValidNamespace(name: String?): Boolean =
            name?.trim()?.matches(NAMESPACE_REGEX) == true
    }
}
