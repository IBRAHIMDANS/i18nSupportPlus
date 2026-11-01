package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * The translation dialog's key: the field, the namespace greyed in front of it, the live status under
 * it and, in CREATE mode, the Namespace combo and its "+" button.
 *
 * The key is checked while it is typed rather than at OK time: available, already taken (which stays
 * confirmable — correcting a value is what the dialog is for) or malformed. [onNamespaceSelected] is
 * told of every namespace the combo or the "+" button selects: the dialog rebuilds its rows for it,
 * then calls [scopeTo] with the files it shows.
 */
internal class DialogKeyField(
    project: Project,
    private val fullKey: FullKey,
    private val mode: Mode,
    private val viewModel: DialogViewModel,
    private val onNamespaceSelected: (String) -> Unit,
) {

    /**
     * Empty when the project stores flat keys: the parser never splits such a key, so a namespace
     * prepended to it was written as part of the key itself (`translation:menu.home`). The combo
     * still picks the files the key goes to; the key only names itself.
     */
    private val nsSeparator: String =
        Settings.getInstance(project).config().let { if (it.usesFlatKeys()) "" else it.nsSeparator }

    /**
     * Empty when the project stores flat keys: there is then nothing to split, and a dot inside
     * a key is a character like any other rather than a level change.
     */
    private val keySeparator: String =
        Settings.getInstance(project).config().let { if (it.usesFlatKeys()) "" else it.keySeparator }

    /**
     * What a CREATE dialog opens on — see [DialogViewModel.initialCreateState]. In EDIT mode
     * the field shows the key whole and the namespace is the key's own.
     */
    private val initialState: Pair<String?, String>? =
        if (mode == Mode.CREATE) DialogViewModel.initialCreateState(fullKey, nsSeparator, Settings.getInstance(project).config().keySeparator) else null

    /** The namespace a CREATE dialog opened on; null in EDIT mode. */
    val initialNamespace: String? = initialState?.first

    val field = JBTextField(initialState?.second ?: fullKey.source)
    val statusLabel = JBLabel()
    private val prefixLabel = JBLabel()

    /**
     * The namespace prefix and the key field as one control.
     *
     * They are held together by a [BorderLayout] rather than by two DSL cells so that the row
     * below — the live status — sits in the same grid column as the whole field and can use its
     * full width, instead of being clipped to the width of the prefix.
     */
    val control = JPanel(BorderLayout(JBUI.scale(PREFIX_GAP), 0)).apply {
        add(prefixLabel, BorderLayout.WEST)
        add(field, BorderLayout.CENTER)
    }

    private var namespaceCombo: JComboBox<String>? = null

    /** Keys already defined under the selected namespace; refreshed together with the sources. */
    private var keysInNamespace: Set<String> = emptySet()

    /** Keys of the selected namespace that only other modules define, with their names; refreshed with the sources. */
    private var keysInOtherModules: Map<String, List<String>> = emptyMap()

    /** Makes the field live: [onChange] runs after the status on each keystroke. */
    fun install(onChange: () -> Unit) {
        field.isEditable = (mode == Mode.CREATE)
        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                refreshStatus()
                onChange()
            }
        })
        prefixLabel.foreground = NamedColorUtil.getInactiveTextColor()
    }

    /**
     * The namespace combo, which owns the namespace the key will be written under.
     * A [JComboBox] built over a non-empty array selects its first item, so the initial
     * selection is the one the sources are first built for.
     */
    fun namespaceComboBox(): JComboBox<String> {
        val namespaces = viewModel.loadNamespaces()
        val combo = JComboBox(namespaces.toTypedArray())
        namespaceCombo = combo
        // Selected before the listener is attached: the initial rows are built once, below.
        if (initialNamespace in namespaces) combo.selectedItem = initialNamespace
        combo.addActionListener {
            val selected = combo.selectedItem as? String ?: return@addActionListener
            onNamespaceSelected(selected)
        }
        return combo
    }

    /**
     * The "+" button creating a namespace without leaving the dialog.
     */
    fun addNamespaceButton(): JButton {
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
            onNamespaceSelected(input)
        }
        return addButton
    }

    /**
     * Re-reads what [namespace] already defines among [sources], so the key check answers about the
     * namespace actually selected, and shows it in front of the field. Only CREATE mode asks whether a
     * key is free; EDIT mode is looking at one that exists, so it never pays for these reads.
     * [otherModules] when the rows are a module's: without a caller or a tab's module they are the
     * project's, and no other module's file is left out to point to.
     */
    fun scopeTo(namespace: String?, sources: Set<LocalizationSource>, otherModules: Boolean) {
        keysInNamespace = if (mode == Mode.CREATE) viewModel.existingKeys(namespace, sources) else emptySet()
        keysInOtherModules = if (mode == Mode.CREATE && otherModules) viewModel.keysInOtherModules(namespace, sources) else emptyMap()
        prefixLabel.text =
            if (mode == Mode.CREATE && !namespace.isNullOrBlank() && nsSeparator.isNotEmpty()) "$namespace$nsSeparator" else ""
    }

    /**
     * The key as typed, without the namespace the combo contributes: the check and the set of
     * existing keys both speak in keys relative to their namespace.
     */
    private fun keyText(): String {
        val typed = field.text.orEmpty().trim()
        val namespace = selectedNamespace()
        return if (mode == Mode.CREATE && !namespace.isNullOrBlank() && nsSeparator.isNotEmpty()) {
            typed.removePrefix("$namespace$nsSeparator")
        } else {
            typed
        }
    }

    fun selectedNamespace(): String? =
        if (mode == Mode.CREATE) namespaceCombo?.selectedItem as? String else fullKey.ns?.text

    /**
     * Says under the field what the typed key will do, while it is being typed. An empty field
     * says nothing: the dialog opens empty, and shouting at a user who has not typed yet is
     * noise — OK-time validation still refuses it.
     */
    fun refreshStatus() {
        if (mode != Mode.CREATE) {
            statusLabel.text = ""
            statusLabel.icon = null
            return
        }
        when (DialogViewModel.checkKey(keyText(), keySeparator, keysInNamespace)) {
            KeyCheck.EMPTY -> {
                statusLabel.text = ""
                statusLabel.icon = null
            }
            KeyCheck.INVALID_SEGMENT -> {
                statusLabel.text = PluginBundle.message("dialog.translation.key.status.invalid")
                statusLabel.icon = AllIcons.General.Error
            }
            KeyCheck.TAKEN -> {
                statusLabel.text = PluginBundle.message("dialog.translation.key.status.taken")
                statusLabel.icon = AllIcons.General.Warning
            }
            KeyCheck.AVAILABLE -> {
                val elsewhere = keysInOtherModules[keyText().trim()]
                if (elsewhere == null) {
                    statusLabel.text = PluginBundle.message("dialog.translation.key.status.available")
                    statusLabel.icon = AllIcons.General.InspectionsOK
                } else {
                    statusLabel.text = PluginBundle.message(
                        "dialog.translation.key.status.other.module", elsewhere.joinToString { "'$it'" }
                    )
                    statusLabel.icon = AllIcons.General.Information
                }
            }
        }
    }

    /**
     * The key *OK* writes: the one opened in EDIT mode; in CREATE mode the one typed, under the selected
     * namespace — null when it does not parse.
     */
    fun effectiveKey(): FullKey? {
        if (mode != Mode.CREATE) return fullKey
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
        return viewModel.parseKey(fullKeyText)
    }

    /** Why *OK* cannot take the key typed, or null when it can. EDIT mode never refuses its key. */
    fun validate(): ValidationInfo? {
        if (mode != Mode.CREATE) return null
        return when (DialogViewModel.checkKey(keyText(), keySeparator, keysInNamespace)) {
            KeyCheck.EMPTY -> ValidationInfo(PluginBundle.message("dialog.translation.error.key.empty"), field)
            KeyCheck.INVALID_SEGMENT -> ValidationInfo(PluginBundle.message("dialog.translation.key.status.invalid"), field)
            // An existing key is not an error: correcting a translation is what this dialog
            // is for. The warning under the field is the whole of what is owed here.
            KeyCheck.TAKEN, KeyCheck.AVAILABLE -> null
        }
    }

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

        private const val PREFIX_GAP = 2

        /**
         * A namespace names a translation file, so it is restricted to letters, digits and
         * hyphens. The rule is named and reachable rather than inlined in the validator: the
         * validator is a widget listener and cannot be exercised headlessly, and this rule
         * used to live in an after-the-fact error dialog with nothing pinning it.
         */
        fun isValidNamespace(name: String?): Boolean =
            name?.trim()?.matches(NAMESPACE_REGEX) == true
    }
}
