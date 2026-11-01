package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.actions.DeleteI18nKeyAction
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewSources
import com.ibrahimdans.i18n.plugin.review.shown
import com.ibrahimdans.i18n.plugin.review.ui.ReviewActions
import com.ibrahimdans.i18n.plugin.review.ui.ReviewThreadDialog
import com.ibrahimdans.i18n.plugin.translate.MachineTranslationSettings
import com.ibrahimdans.i18n.plugin.translate.TranslationContext
import com.ibrahimdans.i18n.plugin.translate.TranslationProvider
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.text.DefaultHighlighter
import org.jetbrains.concurrency.CancellablePromise

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
 *
 * With machine translation on, *Translate Empty Locales* fills the empty locales and each locale's
 * *Translate* link replaces its own value. An *Instruction* field, shown when an engine's prompt reads
 * one, says what to ask of the translations (a tone, a length). A *Context* field shows what the engines
 * are told of the key — its name, and where the code shows it (`caption of <SettingsOption>`, found
 * off the EDT once the dialog is open) — and is sent as edited; the project's translations next to the
 * key are added to it for each locale ([TranslationContext]).
 *
 * In a module that keeps reviews (its `.i18n-reviews/` folder exists), an EDIT dialog shows under
 * each locale what its review says of the stored value, with a *Review…* link to its thread. A locale
 * *Translate* filled and validated as proposed is marked *to review* in the command of OK, with the
 * source text it was translated from; one retouched by hand is not — whoever changed it has read it.
 * The badge compares the stored value with the key's source in its module's reference locale
 * ([ReferenceLocale.of]), the one the table compares: a value whose source changed since its review
 * reads *source changed*, and *Translate* is how it is translated again.
 */
class TranslationDialog(
    private val project: Project,
    private val fullKey: FullKey,
    private val mode: Mode = Mode.EDIT,
    /** The code file the key is created from, when there is one: its module scopes the files offered. */
    private val caller: PsiFile? = null,
    /** The tool window tab's module, when opened from one: it scopes the files offered as [caller]'s does. */
    private val tabModule: ModuleConfig? = null,
) : DialogWrapper(project) {

    private val viewModel = DialogViewModel(project)
    private val sources: Map<LocalizationSource, String?> = viewModel.loadTranslations(fullKey, caller, tabModule)

    private val textAreas = LinkedHashMap<LocalizationSource, JBTextArea>()
    private val variableWarnings = LinkedHashMap<LocalizationSource, JBLabel>()
    private val variableWarningRows = LinkedHashMap<LocalizationSource, Row>()

    /** Holds the per-locale rows, which are rebuilt whenever the namespace changes. */
    private val sourcesHost = JPanel(BorderLayout())

    /** The key field, its namespace and its live status. */
    private val key = DialogKeyField(project, fullKey, mode, viewModel) { namespace ->
        refreshSources(viewModel.loadSourcesForNamespace(namespace, caller, tabModule), namespace)
    }

    private var copyButton: JButton? = null

    /** The review state under each locale, its thread, and the marks left on machine proposals. */
    private val reviews = DialogReviews(project, fullKey, sources, viewModel, caller, tabModule)

    /** *Translate*, its *Context* and *Instruction* fields, and the proposals it made. */
    private val machine = DialogMachineTranslation(
        project, fullKey, tabModule, key.initialNamespace, viewModel,
        typedKey = { key.field.text },
        textAreas = { textAreas },
        moduleOf = reviews::moduleOf,
    )

    init {
        title =
            if (mode == Mode.CREATE) PluginBundle.message("dialog.translation.title.create")
            else PluginBundle.message("dialog.translation.title.edit")
        init()
        window?.minimumSize = JBUI.size(MIN_WIDTH, MIN_HEIGHT)
    }

    override fun createCenterPanel(): JComponent {
        key.install { machine.refreshContext() }
        machine.start(disposable)

        val content = panel {
            // The prefix sits against the field rather than inside it: a text component renders
            // no greyed prefix of its own, and an icon extension cannot carry a namespace name.
            // Glued to the left of the field, it reads as one control.
            row(PluginBundle.message("dialog.translation.key.label")) {
                cell(key.control).align(AlignX.FILL)
            }
            // Nothing to say about a key that cannot be edited.
            row("") {
                cell(key.statusLabel).align(AlignX.FILL)
            }.visible(mode == Mode.CREATE)

            if (mode == Mode.CREATE) {
                row(PluginBundle.message("dialog.translation.namespace.label")) {
                    cell(key.namespaceComboBox()).gap(RightGap.SMALL)
                    cell(key.addNamespaceButton())
                }
            }

            row {
                cell(sourcesHost).align(Align.FILL)
            }.resizableRow()

            if (machine.enabled) {
                row(PluginBundle.message("dialog.translation.context.label")) {
                    cell(JBScrollPane(machine.contextField)).align(AlignX.FILL).comment(PluginBundle.message("dialog.translation.context.comment"))
                }
            }

            // Left out rather than hidden: a hidden row still showed its comment, under no field.
            if (machine.instructionField.isVisible) {
                row(PluginBundle.message("dialog.translation.instruction.label")) {
                    cell(machine.instructionField).align(AlignX.FILL).comment(PluginBundle.message("dialog.translation.instruction.comment"))
                }
            }

            row {
                copyButton = button(PluginBundle.message("dialog.translation.copy.button")) {
                    copyToEmptyLocales()
                }
                    .comment(PluginBundle.message("dialog.translation.copy.comment"))
                    .component
                cell(machine.translateButton)
            }
            row("") { cell(machine.translateStatus).align(AlignX.FILL) }
        }

        if (mode == Mode.CREATE) {
            val namespace = key.selectedNamespace()
            refreshSources(
                if (namespace == null) emptyMap() else viewModel.loadSourcesForNamespace(namespace, caller, tabModule),
                namespace
            )
        } else {
            refreshSources(sources, fullKey.ns?.text)
        }

        centerPanel = content
        val scrollPane = JBScrollPane(content)
        scrollPane.border = JBUI.Borders.empty()
        scrollPane.preferredSize = JBUI.size(PREFERRED_WIDTH, PREFERRED_HEIGHT)
        return scrollPane
    }

    /**
     * Rebuilds the per-locale rows for [namespace] and re-reads what that namespace already
     * defines, so the key check answers about the namespace actually selected.
     */
    private fun refreshSources(sourcesMap: Map<LocalizationSource, String?>, namespace: String?) {
        textAreas.clear()
        variableWarnings.clear()
        variableWarningRows.clear()
        machine.proposals.clear()
        reviews.badges.clear()
        key.scopeTo(namespace, sourcesMap.keys, otherModules = caller != null || tabModule != null)

        sourcesHost.removeAll()
        sourcesHost.add(buildSourceRows(sourcesMap), BorderLayout.CENTER)
        sourcesHost.revalidate()
        sourcesHost.repaint()

        key.refreshStatus()
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
                // Beside the button, which only fills the empty locales: this one replaces a value too.
                if (machine.enabled) {
                    link(PluginBundle.message("dialog.translation.translate.locale")) { translateLocale(source.localeLabel()) }
                        .align(AlignY.TOP)
                        .applyToComponent { toolTipText = PluginBundle.message("dialog.translation.translate.locale.tooltip") }
                }
            }
            variableWarningRows[source] = row("") {
                cell(warning).align(AlignX.FILL)
            }.visible(false)

            // A key being created has no review yet: the badge and its link are for one that exists.
            val root = if (mode == Mode.EDIT) reviews.rootOf(source) else null
            if (root != null) {
                val badge = reviews.badgeOf(source, root)
                row("") {
                    cell(badge)
                    link(PluginBundle.message("dialog.translation.review.link")) { reviews.open(source, root) }
                }
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
        machine.translateButton.isEnabled = copyButton?.isEnabled == true
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

    /** *Translate Empty Locales*, as its button does it. */
    internal fun translateEmptyLocales() = machine.translateEmptyLocales()

    /** The *Translate* link of [locale]. */
    internal fun translateLocale(locale: String) = machine.translateLocale(locale)

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

    /**
     * EDIT only: *Delete*, left of OK / Cancel, removes the key the way *Delete i18n Key* does —
     * every locale shown, a plural with all its forms, the usages listed before a key still in
     * use goes. Built here rather than in a property: [init] asks for it before the class's
     * later properties exist.
     */
    override fun createLeftSideActions(): Array<Action> = leftSideActions()

    /** The actions left of OK / Cancel; readable from tests, where a dialog has no buttons to find. */
    internal fun leftSideActions(): Array<Action> =
        if (mode != Mode.EDIT) emptyArray()
        else arrayOf(object : DialogWrapperAction(PluginBundle.message("dialog.translation.delete")) {
            override fun doAction(e: ActionEvent?) = deleteKey()
        })

    /**
     * Deletes the key once the user confirms, then closes with OK: the panels that opened the
     * dialog reload on OK, as they do after an edit. Cancelled, the dialog stays open.
     */
    internal fun deleteKey() {
        val title = PluginBundle.message("action.delete.key.title")
        val module = DeleteI18nKeyAction.moduleHolding(sources.filterValues { it != null }.keys, Settings.getInstance(project).config())
        val plan = ProgressManager.getInstance().runProcessWithProgressSynchronously<DeleteI18nKeyAction.Plan, RuntimeException>(
            { ReadAction.compute<DeleteI18nKeyAction.Plan, RuntimeException> { DeleteI18nKeyAction.planOf(project, fullKey.source, module) } },
            title,
            true,
            project
        )
        if (DeleteI18nKeyAction.confirmAndDelete(project, plan, title)) close(OK_EXIT_CODE)
    }

    override fun doOKAction() {
        val effectiveKey = key.effectiveKey() ?: return
        // One command for every locale written and for the review marks: one Ctrl+Z undoes the
        // dialog as a whole. No confirmation: a review file is a by-product of the values.
        CommandProcessor.getInstance().executeCommand(project, {
            val written = LinkedHashMap<LocalizationSource, String>()
            textAreas.forEach { (source, textArea) ->
                val currentValue = sources[source]
                val newValue = textArea.text
                // Save only if there is a non-blank value different from the original
                if (newValue.isNotBlank() && newValue != currentValue) {
                    viewModel.saveTranslation(source, effectiveKey, newValue)
                    written[source] = newValue
                }
            }
            reviews.markMachineValues(effectiveKey, written, machine.proposals)
        }, PluginBundle.message("dialog.translation.command.update"), null, UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION)
        super.doOKAction()
    }

    /** OK as its button does it, validation aside; for tests, where the dialog has no button. */
    internal fun confirm() = doOKAction()

    override fun doValidate(): ValidationInfo? {
        key.validate()?.let { return it }
        val hasAnyValue = textAreas.values.any { it.text.isNotBlank() }
        if (!hasAnyValue) {
            return ValidationInfo(PluginBundle.message("dialog.translation.error.value.required"))
        }
        return null
    }

    override fun getPreferredFocusedComponent(): JComponent =
        if (mode == Mode.CREATE) key.field else textAreas.values.firstOrNull() ?: key.field

    /** The size the user last gave the dialog is kept: with many locales, it was redone on every opening (#687). */
    override fun getDimensionServiceKey(): String = DIMENSION_KEY

    /** The value shown for [locale], for tests. */
    internal fun valueOf(locale: String): String? = textAreas.entries.firstOrNull { it.key.localeLabel() == locale }?.value?.text

    /** Types [text] into the key field, for tests. */
    internal fun setKey(text: String) {
        key.field.text = text
    }

    internal fun setValue(locale: String, value: String) {
        textAreas.entries.first { it.key.localeLabel() == locale }.value.text = value
    }

    internal val translateVisible: Boolean get() = machine.enabled

    internal val translateMessage: String get() = machine.translateStatus.text

    internal val instructionVisible: Boolean get() = machine.instructionField.isVisible

    /** The search for where the code shows the key, for a test to wait on; null when the engine is off. */
    internal val uiRolesSearch: CancellablePromise<List<String>>? get() = machine.uiRolesSearch

    /** The rows as built: a headless dialog has no content pane to walk. */
    internal var centerPanel: JComponent? = null
        private set

    internal fun setInstruction(text: String) {
        machine.instructionField.text = text
    }

    /** The context shown, for tests; empty when the engine is off. */
    internal val contextText: String get() = machine.contextField.text

    /** Types [text] into the context field, for tests: as the user editing it. */
    internal fun setContext(text: String) {
        machine.contextField.text = text
    }

    /** The review state shown under [locale], or null when it shows none — nor a *Review…* link. */
    internal fun reviewStateOf(locale: String): String? = reviews.stateOf(locale)

    /** Clicks the *Review…* link of [locale]; false when it has none (reviews off, or CREATE mode). */
    internal fun openReviewOf(locale: String): Boolean = reviews.openOf(locale)

    /**
     * A machine translation as *Translate* proposed it: its [text], its [origin] (`machine:<engine>`)
     * and the [source] it was translated from — the locale and text of the request, a plural form's
     * `other` when it stood in for a missing form ([ReviewSources]).
     */
    internal data class MachineProposal(val text: String, val origin: String, val source: ReviewSource? = null)

    companion object {
        /**
         * The project's machine translation, or null when it has not opted in. A test swaps it for
         * a scripted engine: nothing here reaches the network.
         */
        internal var machineTranslator: (Project) -> TranslationProvider? = { MachineTranslationSettings.getInstance(it).provider() }

        /** Whether an engine of the project reads the dialog's instruction; swapped by a test like [machineTranslator]. */
        internal var machineReadsInstruction: (Project) -> Boolean = { MachineTranslationSettings.getInstance(it).readsInstruction() }

        /**
         * The directory a module's reviews hang under, null when it keeps none. A test points it at
         * the fixture: a project-relative module root is not where a fixture's files are.
         */
        internal var reviewRoot: (Project, ModuleConfig?) -> VirtualFile? = { project, module -> ReviewActions.enabledRoot(project, module) }

        /** Shows the review thread the *Review…* link opens; a test acts on the dialog, and disposes it, instead. */
        internal var reviewThread: (ReviewThreadDialog) -> Unit = { it.show() }

        /**
         * The [proposals] validated as they were: those whose locale was [written] with exactly the
         * proposed text. One retouched, or not written at all, is left out.
         */
        internal fun <K> untouchedProposals(proposals: Map<K, MachineProposal>, written: Map<K, String>): Map<K, MachineProposal> =
            DialogMachineTranslation.untouchedProposals(proposals, written)

        /**
         * The plural category [key] names and the key of its `other` form, or null for a key that is
         * not a plural form: `common:file_few` gives `few` and `common:file_other`, a nested
         * `common:file.few` gives `few` and `common:file.other`.
         */
        internal fun pluralFormOf(key: String, keySeparator: String, pluralSeparator: String): Pair<String, String>? =
            DialogMachineTranslation.pluralFormOf(key, keySeparator, pluralSeparator)

        private const val TEXT_AREA_ROWS = 3
        private const val TEXT_AREA_COLUMNS = 40

        // Sizes go through JBUI so a HiDPI screen scales them instead of truncating the dialog.
        private const val MIN_WIDTH = 520
        private const val MIN_HEIGHT = 300
        private const val PREFERRED_WIDTH = 620
        private const val PREFERRED_HEIGHT = 420
        private const val DIMENSION_KEY = "com.ibrahimdans.i18n.TranslationDialog"

        /** Light enough to read black or white text through it, in either theme. */
        private val VARIABLE_HIGHLIGHT = JBColor(0xFFF2C4, 0x4C4426)

        /** Whether [name] can name a namespace: see [DialogKeyField.isValidNamespace]. */
        internal fun isValidNamespace(name: String?): Boolean = DialogKeyField.isValidNamespace(name)
    }
}
