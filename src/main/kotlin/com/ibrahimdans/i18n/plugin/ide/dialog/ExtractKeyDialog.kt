package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractAnswer
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractKeyModel
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.psi.PsiElement
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * *Extract i18n key* in one window: reuse a key already holding the text, or pick a namespace,
 * name the key and fill the locales — the reference one starts with the text — while the call
 * the code will receive is previewed underneath.
 *
 * It replaces a chain of up to five prompts: *Text Already Translated*, *Input i18n key*, a bare
 * *Translation Value*, a popup listing every translation file of the project, then *Fill
 * Remaining Locales*. The file picked in the popup never reached the code, and the last prompt
 * wrote the key into every namespace.
 *
 * Reads nothing itself: [ExtractKeyModel] was loaded outside the EDT. Writes nothing either —
 * [answer] says what was decided, and the action applies it in one command with the code.
 */
internal class ExtractKeyDialog(
    private val project: Project,
    private val model: ExtractKeyModel,
    private val caller: PsiElement,
) : DialogWrapper(project) {

    /** What OK decided; null until then, and after Cancel. */
    var answer: ExtractAnswer? = null
        private set

    private val offered = model.existingKeys.take(MAX_OFFERED_KEYS)
    private val reuseButtons = offered.map { JBRadioButton(PluginBundle.message("action.intention.extract.key.reuse.option", it)) }
    private val createButton = JBRadioButton(PluginBundle.message("action.intention.extract.key.reuse.create"))

    private val namespaceCombo = ComboBox(model.namespaces.toTypedArray())
    private val addNamespaceButton = JButton("+")
    private val prefixLabel = JBLabel()
    /** The group the key goes under, completed from the groups of the selected namespace. */
    private val parentField = TextFieldWithAutoCompletion.create(
        project, model.parents(model.initialNamespace), true, model.initialParent(model.initialNamespace)
    )
    private val separatorLabel = JBLabel(model.keySeparator)
    private val keyField = JBTextField(model.proposedName(model.initialNamespace))
    private val keyStatus = JBLabel()
    private val localesHost = JPanel(BorderLayout())
    private val fields = LinkedHashMap<LocalizationSource, JBTextField>()
    private val leaveEmptyButton = JBRadioButton(PluginBundle.message("dialog.extract.empty.leave"), true)
    private val copyReferenceButton = JBRadioButton(PluginBundle.message("dialog.extract.empty.copy"))
    private val previewLabel = JBLabel()

    init {
        title = PluginBundle.message("dialog.extract.title")
        setOKButtonText(PluginBundle.message("dialog.extract.ok"))
        // One key holding the text is the obvious intent; with several, choosing is the user's call.
        (if (offered.size == 1) reuseButtons.first() else createButton).isSelected = true
        model.initialNamespace?.let { namespaceCombo.selectedItem = it }
        init()
    }

    override fun createCenterPanel(): JComponent {
        (reuseButtons + createButton).forEach { it.addActionListener { refresh() } }
        var proposed = keyField.text
        var proposedParent = parentField.text
        namespaceCombo.addActionListener {
            parentField.setVariants(model.parents(namespace()))
            if (parentField.text == proposedParent) parentField.text = model.initialParent(namespace()).also { proposedParent = it }
            // A name the user has not touched follows the style of the namespace selected.
            if (keyField.text == proposed) keyField.text = model.proposedName(namespace()).also { proposed = it }
            rebuildLocales()
            refresh()
        }
        parentField.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) = refresh()
        })
        parentField.setPlaceholder(PluginBundle.message("dialog.extract.parent.placeholder"))
        addNamespaceButton.toolTipText = PluginBundle.message("toolwindow.action.add.namespace")
        addNamespaceButton.addActionListener { addNamespace() }
        keyField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refresh()
        })
        prefixLabel.foreground = NamedColorUtil.getInactiveTextColor()
        separatorLabel.foreground = NamedColorUtil.getInactiveTextColor()
        previewLabel.font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)

        // `account:` [parent] `.` [name]: the key reads as the code will write it. Flat keys have
        // no levels, hence no parent.
        val hasLevels = model.keySeparator.isNotEmpty()
        val nameControl = JPanel(BorderLayout(JBUI.scale(PREFIX_GAP), 0)).apply {
            if (hasLevels) add(separatorLabel, BorderLayout.WEST)
            add(keyField, BorderLayout.CENTER)
        }
        val keyControl = JPanel(BorderLayout(JBUI.scale(PREFIX_GAP), 0)).apply {
            add(prefixLabel, BorderLayout.WEST)
            if (hasLevels) {
                add(JPanel(java.awt.GridLayout(1, 2, JBUI.scale(PREFIX_GAP), 0)).apply {
                    add(parentField)
                    add(nameControl)
                }, BorderLayout.CENTER)
            } else {
                add(keyField, BorderLayout.CENTER)
            }
        }
        val content = panel {
            row(PluginBundle.message("dialog.extract.text.label")) {
                label("« ${model.text.ellipsised()} »")
            }
            if (offered.isNotEmpty()) {
                // The UI DSL only takes radio buttons inside a buttonsGroup, which groups them.
                buttonsGroup(PluginBundle.message("dialog.extract.existing.label")) {
                    reuseButtons.forEach { button -> row { cell(button) } }
                    val hidden = model.existingKeys.size - offered.size
                    if (hidden > 0) row { comment(PluginBundle.message("action.intention.extract.key.reuse.more", hidden)) }
                    row { cell(createButton) }
                }
            }
            row(PluginBundle.message("dialog.translation.namespace.label")) {
                cell(namespaceCombo).gap(RightGap.SMALL)
                cell(addNamespaceButton)
            }
            row(PluginBundle.message("dialog.translation.key.label")) {
                cell(keyControl).align(AlignX.FILL)
            }
            row("") { cell(keyStatus).align(AlignX.FILL) }
            row { cell(localesHost).align(Align.FILL) }.resizableRow()
            buttonsGroup {
                row(PluginBundle.message("dialog.extract.empty.label")) {
                    cell(leaveEmptyButton)
                    cell(copyReferenceButton)
                }
            }
            separator()
            row(PluginBundle.message("dialog.extract.code.label")) {
                cell(previewLabel).align(AlignX.FILL)
            }
        }
        rebuildLocales()
        refresh()
        return JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            preferredSize = JBUI.size(PREFERRED_WIDTH, PREFERRED_HEIGHT)
        }
    }

    private fun namespace(): String? = namespaceCombo.selectedItem as? String

    private fun reused(): String? = offered.getOrNull(reuseButtons.indexOfFirst { it.isSelected })

    /** The key as typed — parent and name — without the namespace prefix, in case it was typed too. */
    private fun keyText(): String = model.join(parentField.text.trim().removePrefix(model.prefix(namespace())), keyField.text)

    /**
     * One field per file of the selected namespace, the reference locale first and holding the
     * text. Values already typed are kept, by locale, across namespace changes.
     */
    private fun rebuildLocales() {
        val typed = fields.entries.associate { (source, field) -> source.localeLabel() to field.text }
        fields.clear()
        localesHost.removeAll()
        localesHost.add(panel {
            model.sources(namespace()).forEach { source ->
                val locale = source.localeLabel()
                val initial = typed[locale] ?: if (locale == model.referenceLocale || fields.isEmpty()) model.text else ""
                val field = JBTextField(initial)
                fields[source] = field
                val title =
                    if (locale == model.referenceLocale) PluginBundle.message("dialog.extract.locale.reference", locale) else locale
                row(title) { cell(field).align(AlignX.FILL).comment(source.displayPath) }
            }
        }, BorderLayout.CENTER)
        localesHost.revalidate()
        localesHost.repaint()
    }

    /** Enables what the choice needs, and says what the key and the code will be. */
    private fun refresh() {
        val reused = reused()
        listOf(namespaceCombo, addNamespaceButton, parentField, keyField, leaveEmptyButton, copyReferenceButton)
            .forEach { it.isEnabled = reused == null }
        fields.values.forEach { it.isEnabled = reused == null }
        prefixLabel.text = model.prefix(namespace())
        val check = if (reused == null) model.checkKey(namespace(), keyText()) else KeyCheck.AVAILABLE
        val conflict = if (reused == null) model.conflict(namespace(), keyText())?.let(::message) else null
        keyStatus.text = if (conflict != null) conflict else when (check) {
            KeyCheck.EMPTY -> ""
            KeyCheck.INVALID_SEGMENT -> PluginBundle.message("dialog.translation.key.status.invalid")
            KeyCheck.TAKEN -> PluginBundle.message("dialog.translation.key.status.taken")
            KeyCheck.AVAILABLE -> if (reused == null) PluginBundle.message("dialog.translation.key.status.available") else ""
        }
        keyStatus.icon = when {
            keyStatus.text.isEmpty() -> null
            conflict != null || check == KeyCheck.INVALID_SEGMENT -> AllIcons.General.Error
            check == KeyCheck.TAKEN -> AllIcons.General.Warning
            else -> AllIcons.General.InspectionsOK
        }
        previewLabel.text = when {
            reused != null -> model.reusePreview(reused)
            keyText().isEmpty() -> ""
            else -> model.preview(namespace(), keyText())
        }
    }

    /**
     * Creates a namespace without leaving the dialog. The prompt is anchored to the button: a
     * project-anchored one would open under this modal dialog.
     */
    private fun addNamespace() {
        val name = Messages.showInputDialog(
            addNamespaceButton,
            PluginBundle.message("toolwindow.action.add.namespace.prompt"),
            PluginBundle.message("toolwindow.action.add.namespace"),
            null,
            null,
            object : InputValidator {
                override fun checkInput(inputString: String?) = TranslationDialog.isValidNamespace(inputString)
                override fun canClose(inputString: String?) = TranslationDialog.isValidNamespace(inputString)
            }
        )?.trim()
        if (name.isNullOrBlank()) return
        val viewModel = DialogViewModel(project)
        viewModel.createNamespace(name)
        model.addNamespace(name, viewModel.sourcesFor(listOf(name), caller))
        if ((0 until namespaceCombo.itemCount).none { namespaceCombo.getItemAt(it) == name }) namespaceCombo.addItem(name)
        // Selecting the item fires the listener, which rebuilds the fields.
        namespaceCombo.selectedItem = name
    }

    override fun doValidate(): ValidationInfo? {
        if (reused() != null) return null
        when (model.checkKey(namespace(), keyText())) {
            KeyCheck.EMPTY -> return ValidationInfo(PluginBundle.message("dialog.translation.error.key.empty"), keyField)
            KeyCheck.INVALID_SEGMENT -> return ValidationInfo(PluginBundle.message("dialog.translation.key.status.invalid"), keyField)
            KeyCheck.TAKEN, KeyCheck.AVAILABLE -> Unit
        }
        model.conflict(namespace(), keyText())?.let { return ValidationInfo(message(it), keyField) }
        if (fields.values.none { it.text.isNotBlank() }) {
            return ValidationInfo(PluginBundle.message("dialog.translation.error.value.required"), fields.values.firstOrNull())
        }
        return null
    }

    override fun doOKAction() {
        answer = reused()?.let { ExtractAnswer.Reuse(it) } ?: ExtractAnswer.Create(
            namespace(),
            keyText(),
            fields.mapValues { it.value.text },
            copyReference = copyReferenceButton.isSelected
        )
        super.doOKAction()
    }

    override fun getPreferredFocusedComponent(): JComponent = keyField.also { it.selectAll() }

    private fun message(conflict: ExtractKeyModel.Conflict): String = when (conflict) {
        is ExtractKeyModel.Conflict.LeafAsParent -> PluginBundle.message("dialog.extract.conflict.leaf", conflict.path)
        is ExtractKeyModel.Conflict.GroupAsLeaf -> PluginBundle.message("dialog.extract.conflict.group", conflict.path)
    }

    private fun String.ellipsised(): String = if (length <= MAX_TEXT_SHOWN) this else take(MAX_TEXT_SHOWN - 1) + "…"

    private companion object {
        /** Existing keys offered as choices; the rest are counted. */
        const val MAX_OFFERED_KEYS = 5
        const val MAX_TEXT_SHOWN = 80
        const val PREFIX_GAP = 2
        const val PREFERRED_WIDTH = 620
        const val PREFERRED_HEIGHT = 420
    }
}
