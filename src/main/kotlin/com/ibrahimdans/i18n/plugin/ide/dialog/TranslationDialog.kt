package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.actions.DeleteI18nKeyAction
import com.ibrahimdans.i18n.plugin.ide.actions.MachineFill
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.ReviewBadge
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.review.shown
import com.ibrahimdans.i18n.plugin.review.ui.ReviewActions
import com.ibrahimdans.i18n.plugin.review.ui.ReviewCell
import com.ibrahimdans.i18n.plugin.review.ui.ReviewThreadDialog
import com.ibrahimdans.i18n.plugin.translate.MachineTranslationSettings
import com.ibrahimdans.i18n.plugin.translate.PluralTranslationPlan
import com.ibrahimdans.i18n.plugin.tree.PluralCategories
import com.ibrahimdans.i18n.plugin.translate.Translation
import com.ibrahimdans.i18n.plugin.translate.TranslationProvider
import com.ibrahimdans.i18n.plugin.translate.TranslationRequest
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.Action
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
 *
 * With machine translation on, *Translate Empty Locales* fills the empty locales and each locale's
 * *Translate* link replaces its own value. An *Instruction* field, shown when an engine's prompt reads
 * one, says what to ask of the translations (a tone, a length).
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
) : DialogWrapper(project) {

    private val viewModel = DialogViewModel(project)
    private val sources: Map<LocalizationSource, String?> = viewModel.loadTranslations(fullKey)

    private val textAreas = LinkedHashMap<LocalizationSource, JBTextArea>()
    private val variableWarnings = LinkedHashMap<LocalizationSource, JBLabel>()
    private val variableWarningRows = LinkedHashMap<LocalizationSource, Row>()

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
    private val initialNamespace: String? =
        if (mode == Mode.CREATE) DialogViewModel.initialCreateState(fullKey, nsSeparator, Settings.getInstance(project).config().keySeparator).first else null

    private val keyField = JBTextField(
        if (mode == Mode.CREATE) DialogViewModel.initialCreateState(fullKey, nsSeparator, Settings.getInstance(project).config().keySeparator).second else fullKey.source
    )
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

    /** Shown once the project opted in to machine translation and configured an engine. */
    private val translateButton = JButton(PluginBundle.message("dialog.translation.translate.button")).apply {
        name = "dialog.translation.translate"
        isVisible = machineTranslator(project) != null
        addActionListener { translateEmptyLocales() }
    }

    /**
     * What the user asks of the translations (a tone, a length); shown only when an engine of the
     * project reads it, so that a DeepL-only project is not offered a field that changes nothing.
     */
    private val instructionField = JBTextField().apply {
        name = "dialog.translation.instruction"
        emptyText.text = PluginBundle.message("dialog.translation.instruction.empty")
        isVisible = translateButton.isVisible && machineReadsInstruction(project)
    }

    /** Why the last machine translation left a locale empty, one line per locale. */
    private val translateStatus = JBLabel().apply { foreground = JBColor.RED }

    /** Keys already defined under the selected namespace; refreshed together with the sources. */
    private var keysInNamespace: Set<String> = emptySet()

    /** What *Translate* filled each locale with, and the engine it came from; dropped with the rows. */
    private val machineProposals = LinkedHashMap<LocalizationSource, MachineProposal>()

    /** The review state shown under each locale; only for the locales of a module that keeps reviews. */
    private val reviewBadges = LinkedHashMap<LocalizationSource, JBLabel>()

    /** Where each source's reviews live, null when its module keeps none; read once per dialog. */
    private val reviewRoots = HashMap<LocalizationSource, VirtualFile?>()

    /** The module each source belongs to, null when no module of the settings holds it; read once per dialog. */
    private val owners = HashMap<LocalizationSource, ModuleConfig?>()

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

            row(PluginBundle.message("dialog.translation.instruction.label")) {
                cell(instructionField).align(AlignX.FILL).comment(PluginBundle.message("dialog.translation.instruction.comment"))
            }.visible(instructionField.isVisible)

            row {
                copyButton = button(PluginBundle.message("dialog.translation.copy.button")) {
                    copyToEmptyLocales()
                }
                    .comment(PluginBundle.message("dialog.translation.copy.comment"))
                    .component
                cell(translateButton)
            }
            row("") { cell(translateStatus).align(AlignX.FILL) }
        }

        if (mode == Mode.CREATE) {
            val namespace = selectedNamespace()
            refreshSources(
                if (namespace == null) emptyMap() else viewModel.loadSourcesForNamespace(namespace, caller),
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
        val namespaces = viewModel.loadNamespaces()
        val combo = JComboBox(namespaces.toTypedArray())
        namespaceCombo = combo
        // Selected before the listener is attached: the initial rows are built once, below.
        if (initialNamespace in namespaces) combo.selectedItem = initialNamespace
        combo.addActionListener {
            val selected = combo.selectedItem as? String ?: return@addActionListener
            refreshSources(viewModel.loadSourcesForNamespace(selected, caller), selected)
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
            refreshSources(viewModel.loadSourcesForNamespace(input, caller), input)
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
        machineProposals.clear()
        reviewBadges.clear()
        // Only CREATE mode asks whether a key is free; EDIT mode is looking at one that exists,
        // so it never pays for the project-wide read behind existingKeys().
        keysInNamespace = if (mode == Mode.CREATE) viewModel.existingKeys(namespace) else emptySet()
        namespacePrefixLabel.text =
            if (mode == Mode.CREATE && !namespace.isNullOrBlank() && nsSeparator.isNotEmpty()) "$namespace$nsSeparator" else ""

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
                // Beside the button, which only fills the empty locales: this one replaces a value too.
                if (translateButton.isVisible) {
                    link(PluginBundle.message("dialog.translation.translate.locale")) { translateLocale(source.localeLabel()) }
                        .align(AlignY.TOP)
                        .applyToComponent { toolTipText = PluginBundle.message("dialog.translation.translate.locale.tooltip") }
                }
            }
            variableWarningRows[source] = row("") {
                cell(warning).align(AlignX.FILL)
            }.visible(false)

            // A key being created has no review yet: the badge and its link are for one that exists.
            val root = if (mode == Mode.EDIT) reviewRootOf(source) else null
            if (root != null) {
                val badge = JBLabel()
                reviewBadges[source] = badge
                showReview(source, root, badge)
                row("") {
                    cell(badge)
                    link(PluginBundle.message("dialog.translation.review.link")) { openReview(source, root) }
                }
            }
        }
    }

    /**
     * The directory [source]'s reviews hang under — its module's, see [ReviewActions.enabledRoot] —
     * or null when that module keeps no reviews. Never creates anything.
     */
    private fun reviewRootOf(source: LocalizationSource): VirtualFile? {
        if (source in reviewRoots) return reviewRoots[source]
        return reviewRoot(project, moduleOf(source)).also { reviewRoots[source] = it }
    }

    /** The module [source]'s file belongs to, or null when no module of the settings holds it. */
    private fun moduleOf(source: LocalizationSource): ModuleConfig? {
        if (source in owners) return owners[source]
        val modules = Settings.getInstance(project).config().modules
        val module = if (modules.isEmpty()) null else ModuleSources.owner(modules, TranslationDataLoader.projectPathOf(source))
        return module.also { owners[source] = it }
    }

    /**
     * What [source]'s stored value is compared against: the same key, as stored, in the reference
     * locale of [source]'s module — [ReferenceLocale.of] over that module's locales, as the table
     * reads it — as its locale and its text. Null for the reference locale itself, and when the
     * reference holds no value.
     */
    private fun currentSourceOf(source: LocalizationSource): Pair<String, String>? {
        val module = moduleOf(source)
        val inModule = sources.keys.filter { moduleOf(it) == module }
        val config = Settings.getInstance(project).config()
        val reference = ReferenceLocale.of(module, config, inModule.map { it.localeLabel() }.distinct()) ?: return null
        if (source.localeLabel() == reference) return null
        val text = inModule.firstOrNull { it.localeLabel() == reference }?.let { sources[it] }
        return text?.takeIf { it.isNotEmpty() }?.let { reference to it }
    }

    /**
     * [key] in [source] as the tool window spells it — its namespace left out when it is a default
     * one — so that the dialog and the table read and write the same review.
     */
    private fun reviewSpellingOf(source: LocalizationSource, key: FullKey): String {
        val defaults = Settings.getInstance(project).config().defaultNamespaces()
        val namespace = TranslationDataLoader.extractNamespace(source, defaults.first())
        return ReviewKey(namespace, key.compositeKey.joinToString(".") { it.text }).spelling(defaults)
    }

    private fun reviewKeyOf(source: LocalizationSource, key: FullKey): ReviewKey =
        ReviewKey.fromSpelling(reviewSpellingOf(source, key), Settings.getInstance(project).config().defaultNamespaces().first())

    /** What the review of [source]'s stored value says, in [badge]. */
    private fun showReview(source: LocalizationSource, root: VirtualFile, badge: JBLabel) {
        val review = ReviewStore.getInstance(project).reviews(root, source.localeLabel())[reviewKeyOf(source, fullKey)]
        val current = currentSourceOf(source)?.let { (locale, text) -> ReviewSource.of(locale, text) }
        val state = shown(review, sources[source], current)
        badge.icon = ReviewBadge.icon(state)
        badge.text = if (state == ShownReview.NONE) PluginBundle.message("review.dialog.state.none") else ReviewBadge.label(state)
    }

    /**
     * The review thread of [source]'s locale, on the value stored — not the one being typed — and on
     * the source stored in its module's reference locale, which every decision taken there records.
     */
    private fun openReview(source: LocalizationSource, root: VirtualFile) {
        val current = currentSourceOf(source)
        val cell = ReviewCell(reviewSpellingOf(source, fullKey), source.localeLabel(), sources[source].orEmpty(), current?.second)
        reviewThread(ReviewThreadDialog(project, root, cell, referenceLocale = current?.first))
        reviewBadges[source]?.let { showReview(source, root, it) }
    }

    /** Clicks the *Review…* link of [locale]; false when it has none (reviews off, or CREATE mode). */
    internal fun openReviewOf(locale: String): Boolean {
        val source = reviewBadges.keys.firstOrNull { it.localeLabel() == locale } ?: return false
        openReview(source, reviewRootOf(source) ?: return false)
        return true
    }

    /**
     * The key as typed, without the namespace the combo contributes: the check and the set of
     * existing keys both speak in keys relative to their namespace.
     */
    private fun keyText(): String {
        val typed = keyField.text.orEmpty().trim()
        val namespace = selectedNamespace()
        return if (mode == Mode.CREATE && !namespace.isNullOrBlank() && nsSeparator.isNotEmpty()) {
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
        translateButton.isEnabled = copyButton?.isEnabled == true
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
     * Fills every locale left empty with the machine translation of the reference locale's value. A
     * value already there is never replaced — [translateLocale] is for that.
     */
    internal fun translateEmptyLocales() = translate(textAreas.filterValues { it.text.isBlank() }.keys)

    /**
     * Replaces the value shown for [locale], empty or not, with the machine translation of another
     * locale's: the reference, or another one when [locale] is the reference itself.
     */
    internal fun translateLocale(locale: String) = translate(textAreas.keys.filter { it.localeLabel() == locale }.toSet())

    /**
     * Fills [targetSources] with the machine translation of the reference locale's value, taken among the
     * other locales. Nothing is written to the files before *OK*: the proposals stay editable in their
     * fields, and *Cancel* gives the stored values back.
     *
     * One request per target locale, under a cancellable progress. A locale the engines could not
     * translate keeps its value, and the reason shows under the buttons.
     *
     * A plural form (`file_few`) follows [PluralTranslationPlan]: a locale whose language has no
     * such form (`file_one` in Japanese) is left alone, and when no locale holds the form yet its
     * text comes from the reference locale's `other`, the proposal then flagged for review. An ICU
     * plural is not translated: its branches depend on the target language.
     */
    private fun translate(targetSources: Set<LocalizationSource>) {
        val provider = machineTranslator(project) ?: return
        if (targetSources.isEmpty()) return
        val filled = textAreas.entries.filter { it.key !in targetSources && it.value.text.isNotBlank() }
        // The language translated *from* matters more than for a copy: the reference of the key's
        // module, as the rest of the plugin picks it (its declared one, then the project's: preview
        // locale, else `en`), the fullest locale last. Another module's declared reference is not
        // asked: the source a review mark records has to be the one the table compares.
        val config = Settings.getInstance(project).config()
        val module = moduleOf(targetSources.first())
        // A plural form only when its `other` exists somewhere: `steps.one` is otherwise just a key.
        val plural = pluralFormOf(keyField.text, config.keySeparator, CLDR_SUFFIX_SEPARATOR)
            ?.takeIf { it.first == PluralCategories.OTHER || otherFormSource(it.second, module, config) != null }
        val labels = filled.map { it.key.localeLabel() }.toSet()
        val sourceLocale = ReferenceLocale.of(module, config, labels) ?: viewModel.localeToCopyFrom(textAreas.keys)
        // Among the files of that locale, the one of the key's module: a neighbour's may say otherwise.
        val donor = filled.sortedWith(
            compareByDescending<Map.Entry<LocalizationSource, JBTextArea>> { it.key.localeLabel() == sourceLocale }
                .thenByDescending { moduleOf(it.key) == module }
        ).firstOrNull()
        val source = donor?.let { it.key.localeLabel() to it.value.text }
            ?: plural?.let { otherFormSource(it.second, module, config) }
        // A locale is never translated into itself: the one whose `other` form is the source is left out.
        val targets = textAreas.entries.filter { it.key in targetSources && it.key.localeLabel() != source?.first }
            .map { it.key.localeLabel() to it.value }
        if (source == null || targets.isEmpty()) {
            translateStatus.text = PluginBundle.message("dialog.translation.translate.no.source")
            return
        }

        // What each target locale is asked for: the source text, or a note saying why not. The text
        // is kept as the proposal's source only when it is the key's own: an `other` form standing in
        // for a missing one is another key's, which the table never compares — no source, in v1.
        val asSource = { text: String -> if (donor != null) ReviewSource.of(source.first, text) else null }
        val notes = mutableListOf<String>()
        val requests = targets.mapNotNull { (locale, area) ->
            if (plural == null) return@mapNotNull MachineRequest(locale, area, source.second, asSource(source.second))
            val sourceForms = if (donor != null) mapOf(plural.first to source.second) else mapOf(PluralCategories.OTHER to source.second)
            when (val plan = PluralTranslationPlan.of(sourceForms, locale)) {
                PluralTranslationPlan.Plan.IcuPlural -> {
                    notes += PluginBundle.message("dialog.translation.translate.icu.plural", locale); null
                }
                is PluralTranslationPlan.Plan.Forms -> {
                    val form = plan.forms.firstOrNull { it.category == plural.first }
                    when {
                        form == null -> { notes += PluginBundle.message("dialog.translation.translate.no.such.form", locale, plural.first); null }
                        else -> {
                            if (form.needsReview) notes += PluginBundle.message("dialog.translation.translate.review", locale, plural.first)
                            MachineRequest(locale, area, form.source, if (form.needsReview) null else asSource(form.source))
                        }
                    }
                }
            }
        }
        // Read by the engine, not by the user: English whatever the IDE language.
        val context = "A UI string of an application, under the i18n key ${keyField.text}"
        val instruction = instructionField.text.trim().takeIf { instructionField.isVisible }.orEmpty()
        val results = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<Translation>, RuntimeException>(
            {
                val indicator = ProgressManager.getInstance().progressIndicator
                requests.map { (locale, _, text) ->
                    indicator?.checkCanceled()
                    indicator?.text = PluginBundle.message("dialog.translation.translate.progress", locale)
                    provider.translate(TranslationRequest(listOf(text), source.first, locale, context, instruction), indicator).single()
                }
            },
            PluginBundle.message("dialog.translation.translate.button"),
            true,
            project
        )
        val failures = requests.zip(results).mapNotNull { (request, result) ->
            when (result) {
                is Translation.Done -> {
                    request.area.text = result.text
                    textAreas.entries.firstOrNull { it.value === request.area }?.key
                        ?.let { machineProposals[it] = MachineProposal(result.text, MachineFill.originOf(result), request.source) }
                    null
                }
                is Translation.Failed -> "${request.locale}: ${result.reason}"
            }
        } + notes
        translateStatus.text = failures.joinToString("<br>", "<html>", "</html>").takeIf { failures.isNotEmpty() }.orEmpty()
    }

    /**
     * The `other` form of the plural being edited, [otherKey], as [module]'s reference locale holds
     * it — any locale holding it when the reference does not — or null when none does.
     */
    private fun otherFormSource(otherKey: String, module: ModuleConfig?, config: Config): Pair<String, String>? {
        val values = viewModel.parseKey(otherKey)?.let(viewModel::loadTranslations).orEmpty()
            .mapNotNull { (source, value) -> value?.takeIf { it.isNotBlank() }?.let { source.localeLabel() to it } }
            .toMap()
        val reference = ReferenceLocale.of(module, config, values.keys)
        return (values.entries.firstOrNull { it.key == reference } ?: values.entries.firstOrNull())?.toPair()
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
            markMachineValues(effectiveKey, written)
        }, PluginBundle.message("dialog.translation.command.update"), null, UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION)
        super.doOKAction()
    }

    /**
     * Marks *to review* each locale of [written] holding *Translate*'s proposal unchanged, with the
     * engine it came from and the source it was translated from — in the current command, for its
     * undo. A module keeping no reviews gets nothing, not even a folder: only an explicit choice
     * turns reviews on.
     */
    private fun markMachineValues(key: FullKey, written: Map<LocalizationSource, String>) {
        val toMark = untouchedProposals(machineProposals, written)
            .mapNotNull { (source, proposal) -> reviewRootOf(source)?.let { Triple(source, it, proposal) } }
        if (toMark.isEmpty()) return
        val store = ReviewStore.getInstance(project)
        ApplicationManager.getApplication().runWriteAction {
            toMark.forEach { (source, root, proposal) ->
                val reviewKey = reviewKeyOf(source, key)
                val sources = proposal.source?.let { mapOf(reviewKey to it) }.orEmpty()
                store.mark(root, source.localeLabel(), mapOf(reviewKey to proposal.text), proposal.origin, sources)
            }
        }
        // No editor holds the review file: a global command is what Ctrl+Z reaches from anywhere.
        CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)
    }

    /** OK as its button does it, validation aside; for tests, where the dialog has no button. */
    internal fun confirm() = doOKAction()

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

    /** The value shown for [locale], for tests. */
    internal fun valueOf(locale: String): String? = textAreas.entries.firstOrNull { it.key.localeLabel() == locale }?.value?.text

    /** Types [text] into the key field, for tests. */
    internal fun setKey(text: String) {
        keyField.text = text
    }

    internal fun setValue(locale: String, value: String) {
        textAreas.entries.first { it.key.localeLabel() == locale }.value.text = value
    }

    internal val translateVisible: Boolean get() = translateButton.isVisible

    internal val translateMessage: String get() = translateStatus.text

    internal val instructionVisible: Boolean get() = instructionField.isVisible

    internal fun setInstruction(text: String) {
        instructionField.text = text
    }

    /** The review state shown under [locale], or null when it shows none — nor a *Review…* link. */
    internal fun reviewStateOf(locale: String): String? =
        reviewBadges.entries.firstOrNull { it.key.localeLabel() == locale }?.value?.text

    /**
     * A machine translation as *Translate* proposed it: its [text], its [origin] (`machine:<engine>`)
     * and the [source] it was translated from — the locale and text of the request — null when that
     * text was not the key's own.
     */
    internal data class MachineProposal(val text: String, val origin: String, val source: ReviewSource? = null)

    /** What *Translate* asks for [locale], shown in [area]: [text], kept as the proposal's [source]. */
    private data class MachineRequest(val locale: String, val area: JBTextArea, val text: String, val source: ReviewSource?)

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
            proposals.filter { (key, proposal) -> written[key] == proposal.text }

        /**
         * The plural category [key] names and the key of its `other` form, or null for a key that is
         * not a plural form: `common:file_few` gives `few` and `common:file_other`, a nested
         * `common:file.few` gives `few` and `common:file.other`.
         */
        internal fun pluralFormOf(key: String, keySeparator: String, pluralSeparator: String): Pair<String, String>? {
            val lastSegment = key.substringAfterLast(keySeparator)
            val suffix = lastSegment.substringAfterLast(pluralSeparator, "")
            return when {
                lastSegment.contains(pluralSeparator) && suffix in PluralCategories.ALL ->
                    suffix to key.removeSuffix(pluralSeparator + suffix) + pluralSeparator + PluralCategories.OTHER
                lastSegment in PluralCategories.ALL && key.contains(keySeparator) ->
                    lastSegment to key.removeSuffix(keySeparator + lastSegment) + keySeparator + PluralCategories.OTHER
                else -> null
            }
        }

        /**
         * Between a base and its CLDR category in i18next's flat convention (`item_one`), as in
         * `MissingPluralFormsInspection`. Not [Config.pluralSeparator], which is the older numeric
         * plurals' (`item-0`, `item-1`).
         */
        private const val CLDR_SUFFIX_SEPARATOR = "_"

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
