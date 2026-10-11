package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.dialog.KeyCheck
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.key.parser.KeyParserBuilder
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.psi.PsiFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * What the preview of *Internationalize…* holds, apart from its widgets: one [Row] per text of the
 * plan, checked or not, with its key as typed — and what that key would do ([status]).
 *
 * [existing] gives, for each planned file, the keys its module holds in the reference locale with
 * their value, spelled like [I18nizePlan.Entry.key]: a key typed over one of them reuses it when it
 * holds the same text, and keeps its value — the text then shows another one — when it does not.
 */
internal class I18nizePreviewModel(
    val plan: I18nizePlan,
    private val existing: Map<PsiFile, Map<String, String>>,
    private val config: Config
) {

    inner class Row(val file: PsiFile, val entry: I18nizePlan.Entry) {
        /** Data the plan flags (a URL, an identifier, a date…) starts unchecked. */
        var selected: Boolean = entry.checkedByDefault
        var key: String = entry.key

        val text: String get() = entry.candidate.originalText

        /** The namespace [key] goes to: the one it writes, else the text's own. */
        fun namespace(): String = namespaceOf(key.trim(), entry.namespace)

        /** The value [key] holds in the reference locale already, if any. */
        fun existingValue(): String? = existing[file]?.get(key.trim())

        /** The value the reference locale will hold under [key]: the one there, kept, else the text. */
        fun value(): String = existingValue() ?: text

        fun status(): Status = statusOf(this)
    }

    /** What a row's key would do; [isError] blocks *Apply*. */
    enum class Status(val isError: Boolean) {
        /** A new key, holding the text. */
        NEW(false),
        /** An existing key holding the same text: nothing to write in the translations. */
        REUSED(false),
        /** An existing key holding another text: its value is kept, the text would show it. */
        TAKEN(false),
        EMPTY(true),
        INVALID(true),
        /** Another checked text of another value gets the same key: one of them would be lost. */
        CLASH(true)
    }

    val files: List<Pair<PsiFile, List<Row>>> = plan.files.map { file -> file.file to file.entries.map { Row(file.file, it) } }

    val rows: List<Row> get() = files.flatMap { it.second }

    /** The keys, as [KeyCheck] tells them apart: flat keys have no separator to split on. */
    private val keySeparator = if (config.usesFlatKeys()) "" else config.keySeparator

    private fun statusOf(row: Row): Status {
        val key = row.key.trim()
        val namespace = row.namespace()
        val path = if (config.nsSeparator.isNotEmpty()) key.removePrefix("$namespace${config.nsSeparator}") else key
        when (DialogViewModel.checkKey(path, keySeparator, emptySet())) {
            KeyCheck.EMPTY -> return Status.EMPTY
            KeyCheck.INVALID_SEGMENT -> return Status.INVALID
            KeyCheck.TAKEN, KeyCheck.AVAILABLE -> Unit
        }
        if (row.selected && rows.any { it !== row && it.selected && it.key.trim() == key && it.namespace() == namespace && it.text != row.text }) {
            return Status.CLASH
        }
        val value = row.existingValue() ?: return Status.NEW
        return if (value.trim() == row.text.trim()) Status.REUSED else Status.TAKEN
    }

    /** The first checked row whose key blocks *Apply*. */
    fun firstError(): Row? = rows.firstOrNull { it.selected && it.status().isError }

    /** The plan as accepted: the checked texts only, each with its key as typed. */
    fun accepted(): I18nizePlan = I18nizePlan(files.mapNotNull { (file, rows) ->
        val entries = rows.filter { it.selected }.map { row ->
            val key = row.key.trim()
            if (key == row.entry.key) row.entry
            else row.entry.copy(key = key, namespace = row.namespace(), reused = row.status() == Status.REUSED)
        }
        if (entries.isEmpty()) null else I18nizePlan.FilePlan(file, entries)
    })

    /**
     * The namespace [key] writes, parsed the way [I18nizeApply] parses it; [fallback] when it writes
     * none — the text's own, which its component's hook declares — or when the first component
     * stands for the namespace, which every key then writes.
     */
    private fun namespaceOf(key: String, fallback: String): String {
        if (config.usesFlatKeys() || config.firstComponentNs || key.isEmpty()) return fallback
        val parsed = KeyParserBuilder.withSeparators(config.nsSeparator, config.keySeparator).build()
            .parse(RawKey(listOf(KeyElement.literal(key))), emptyNamespace = false, firstComponentNamespace = false)
        return parsed?.ns?.text?.takeIf { it.isNotBlank() } ?: fallback
    }
}

/**
 * The preview of *Internationalize…*: one collapsible group per file, a row per text — its check
 * box, the text, its key (editable, checked while typed, as in the *Create key* dialog), what the
 * key does (new, reused, holding another value) and the value the reference locale will hold.
 * A file's own check box checks or unchecks all its texts.
 *
 * [translatable] — the project has opted in to machine translation — adds a check box, checked, to
 * translate the new keys into the other locales once *Apply* is pressed.
 */
internal class I18nizePreviewDialog(
    project: Project,
    private val model: I18nizePreviewModel,
    translatable: Boolean = false
) : DialogWrapper(project) {

    /** *Translate the other locales*, there only when the project has opted in to machine translation. */
    internal val translateBox: JBCheckBox? =
        if (translatable) JBCheckBox(PluginBundle.message("action.i18nize.preview.translate"), true) else null

    private val fields = mutableMapOf<I18nizePreviewModel.Row, JBTextField>()
    private val statuses = mutableMapOf<I18nizePreviewModel.Row, JBLabel>()
    private val values = mutableMapOf<I18nizePreviewModel.Row, JBLabel>()
    private val rowBoxes = mutableMapOf<I18nizePreviewModel.Row, JBCheckBox>()
    private val fileBoxes = mutableMapOf<PsiFile, JBCheckBox>()

    init {
        val texts = model.rows.size
        title = PluginBundle.message("action.i18nize.preview.title", texts, model.files.size)
        setOKButtonText(PluginBundle.message("action.i18nize.preview.ok"))
        init()
        initValidation()
        refresh()
    }

    /** The plan as accepted, once the dialog closed with *Apply*. */
    fun accepted(): I18nizePlan = model.accepted()

    /** Whether the new keys are to be machine-translated into the other locales. */
    fun translateOthers(): Boolean = translateBox?.isSelected == true

    override fun createCenterPanel(): JComponent {
        val content = panel {
            model.files.forEach { (file, rows) ->
                val path = file.virtualFile?.presentableUrl ?: file.name
                collapsibleGroup(PluginBundle.message("action.i18nize.preview.file", file.name, rows.size)) {
                    row {
                        val all = JBCheckBox(PluginBundle.message("action.i18nize.preview.all"), rows.any { it.selected })
                        all.toolTipText = path
                        all.addActionListener {
                            rows.forEach { row -> row.selected = all.isSelected; rowBoxes[row]?.isSelected = all.isSelected }
                            refresh()
                        }
                        fileBoxes[file] = all
                        cell(all)
                    }
                    rows.forEach { row -> textRow(row) }
                }.expanded = model.files.size <= EXPANDED_FILES
            }
        }
        val scroll = JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            preferredSize = JBUI.size(WIDTH, HEIGHT)
        }
        val translate = translateBox ?: return scroll
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(scroll, BorderLayout.CENTER)
            add(translate, BorderLayout.SOUTH)
        }
    }

    private fun com.intellij.ui.dsl.builder.Panel.textRow(row: I18nizePreviewModel.Row) {
        row {
            val box = JBCheckBox(row.text.take(TEXT_WIDTH).let { if (it.length < row.text.length) "$it…" else it }, row.selected).apply {
                toolTipText = row.text
                addActionListener {
                    row.selected = isSelected
                    refresh()
                }
            }
            rowBoxes[row] = box
            cell(box)
            val field = JBTextField(row.key, KEY_COLUMNS).apply {
                document.addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) {
                        row.key = text
                        refresh()
                    }
                })
            }
            fields[row] = field
            cell(field).align(AlignX.FILL).resizableColumn()
            statuses[row] = JBLabel().also { cell(it) }
            values[row] = JBLabel().also { cell(it) }
        }
    }

    /**
     * Says, under every key, what it does now — a key typed in one row can clash with another —
     * and keeps each file's box in step with its texts.
     */
    private fun refresh() {
        model.rows.forEach { row ->
            val status = row.status()
            statuses[row]?.apply {
                text = reasonText(row) ?: statusText(row, status)
                icon = when {
                    !row.selected -> null
                    status.isError -> AllIcons.General.Error
                    status == I18nizePreviewModel.Status.TAKEN -> AllIcons.General.Warning
                    else -> null
                }
                isEnabled = row.selected
            }
            values[row]?.apply {
                text = row.value().take(TEXT_WIDTH)
                toolTipText = row.value()
                isEnabled = row.selected
            }
            fields[row]?.isEnabled = row.selected
        }
        model.files.forEach { (file, rows) -> fileBoxes[file]?.isSelected = rows.any { it.selected } }
        isOKActionEnabled = model.accepted().files.isNotEmpty()
    }

    private fun statusText(row: I18nizePreviewModel.Row, status: I18nizePreviewModel.Status): String = when (status) {
        I18nizePreviewModel.Status.NEW -> PluginBundle.message("action.i18nize.status.new")
        I18nizePreviewModel.Status.REUSED -> PluginBundle.message("action.i18nize.status.reused")
        I18nizePreviewModel.Status.TAKEN -> PluginBundle.message("action.i18nize.status.taken")
        I18nizePreviewModel.Status.EMPTY -> PluginBundle.message("dialog.translation.error.key.empty")
        I18nizePreviewModel.Status.INVALID -> PluginBundle.message("dialog.translation.key.status.invalid")
        I18nizePreviewModel.Status.CLASH -> PluginBundle.message(
            "action.i18nize.status.clash",
            model.rows.firstOrNull { it !== row && it.selected && it.key.trim() == row.key.trim() && it.text != row.text }?.text.orEmpty()
        )
    }

    /** Why an unchecked text was offered unchecked: shown instead of what its key would do. */
    private fun reasonText(row: I18nizePreviewModel.Row): String? =
        row.entry.uncheckedReason?.takeIf { !row.selected }?.let { reason ->
            when (reason) {
                TranslatableText.Reason.URL -> PluginBundle.message("action.i18nize.reason.url")
                TranslatableText.Reason.PATH -> PluginBundle.message("action.i18nize.reason.path")
                TranslatableText.Reason.IDENTIFIER -> PluginBundle.message("action.i18nize.reason.identifier")
                TranslatableText.Reason.CSS_CLASS -> PluginBundle.message("action.i18nize.reason.css_class")
                TranslatableText.Reason.COLOR -> PluginBundle.message("action.i18nize.reason.color")
                TranslatableText.Reason.DATE -> PluginBundle.message("action.i18nize.reason.date")
                TranslatableText.Reason.NUMBER -> PluginBundle.message("action.i18nize.reason.number")
                TranslatableText.Reason.SYMBOLS -> PluginBundle.message("action.i18nize.reason.symbols")
            }
        }

    override fun doValidate(): ValidationInfo? {
        val row = model.firstError() ?: return null
        return ValidationInfo(statusText(row, row.status()), fields[row])
    }

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 520
        const val KEY_COLUMNS = 28
        const val TEXT_WIDTH = 40
        /** Up to this many files, every group opens expanded; past it, the list of files comes first. */
        const val EXPANDED_FILES = 5
    }
}
