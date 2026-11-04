package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationFileRouting
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec.ImportEntry
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec.ImportPlan
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginCoroutineScope
import com.ibrahimdans.i18n.plugin.utils.XliffTranslationCodec
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.charset.StandardCharsets
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.table.DefaultTableModel

/**
 * Imports translations from a CSV file produced by [ExportTranslationsAction]
 * (column "key" + one column per locale), or from an XLIFF file (`.xlf`,
 * `.xliff`) a CAT tool sends back — read by [XliffTranslationCodec] into the
 * same records, so both go through one import plan. Shows a mandatory preview of what
 * will be created and updated before writing anything; unknown keys and
 * unknown locale columns are reported and never written. All writes run in
 * a single WriteCommandAction (one undo step).
 */
class ImportTranslationsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val scope = chooseModuleScope(project, PluginBundle.message("action.import.title")) ?: return

        val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
            .withFileFilter { it.extension?.lowercase() in IMPORTABLE_EXTENSIONS }
            .withTitle(PluginBundle.message("action.import.chooser.title"))
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val text = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
        val isXliff = file.extension?.lowercase() in XliffTranslationCodec.EXTENSIONS

        PluginCoroutineScope.of(project).launch {
            LOG.info("Import Translations: started (${file.name})")
            var malformed: String? = null
            val plan = withBackgroundProgress(project, PluginBundle.message("action.import.progress.analyzing"), cancellable = false) {
                coroutineToIndicator {
                    try {
                        val records = if (isXliff) XliffTranslationCodec.parse(text) else CsvTranslationCodec.parse(text)
                        val existing = TranslationDataLoader.loadAllTranslations(project, scope.config)
                        val knownLocales = existing.values.flatMap { it.keys }.distinct()
                        CsvTranslationCodec.computeImportPlan(existing, knownLocales, records)
                    } catch (ex: IllegalArgumentException) {
                        malformed = ex.message ?: PluginBundle.message("action.import.error.malformed")
                        null
                    }
                }
            }
            LOG.info("Import Translations: ${plan?.entries?.size ?: 0} entries planned${if (malformed != null) ", file not read" else ""}")

            val confirmed = withContext(Dispatchers.EDT) {
                when {
                    plan == null -> Messages.showErrorDialog(
                        project,
                        malformed ?: PluginBundle.message("action.import.error.malformed"),
                        PluginBundle.message("action.import.title")
                    )
                    plan.entries.isEmpty() -> Messages.showInfoMessage(project, summaryOf(plan, routed = null), PluginBundle.message("action.import.title"))
                    else -> return@withContext ImportPreviewDialog(project, plan).showAndGet()
                }
                false
            }
            if (confirmed && plan != null) applyPlan(project, plan, scope)
        }
    }

    /**
     * Applies all planned entries in one WriteCommandAction (single undo step). The summary counts
     * the values written and names the entries no file took ([routePlan]).
     */
    private suspend fun applyPlan(project: Project, plan: ImportPlan, scope: ModuleScope) {
        val routed = withBackgroundProgress(project, PluginBundle.message("action.import.progress.title"), cancellable = false) {
            val config = Settings.getInstance(project).config()
            // Scoped to the chosen module: writing against the project-wide source
            // list would route a value into another module's file sharing the same
            // namespace and locale.
            val sources = readAction { TranslationDataLoader.findSources(project, scope.config) }
            val routed = coroutineToIndicator { indicator ->
                indicator.text = PluginBundle.message("action.import.progress.resolving")
                routePlan(plan, sources, config).also {
                    indicator.text = PluginBundle.message("action.import.progress.writing", it.writes.size)
                }
            }

            withContext(Dispatchers.EDT) {
                val viewModel = DialogViewModel(project)
                WriteCommandAction.runWriteCommandAction(
                    project, PluginBundle.message("action.import.command"), null,
                {
                    for ((source, fullKey, entry) in routed.writes) {
                        viewModel.saveTranslation(source, fullKey, entry.value)
                    }
                })
            }
            routed
        }
        LOG.info("Import Translations: ${routed.writes.size} values written, ${routed.unrouted.size} without a file")
        withContext(Dispatchers.EDT) {
            Messages.showInfoMessage(
                project,
                summaryOf(plan, routed),
                PluginBundle.message("action.import.title")
            )
        }
    }

    /** A planned entry with the file it goes to and the key it is written under there. */
    internal data class Write(val source: LocalizationSource, val fullKey: FullKey, val entry: ImportEntry)

    /** [routePlan]'s answer: the writes to make, and the entries no file of the module takes. */
    internal data class RoutedPlan(val writes: List<Write>, val unrouted: List<ImportEntry>)

    companion object {
        private val LOG = Logger.getInstance(ImportTranslationsAction::class.java)

        /** How many of the ignored keys the summary names before trailing off. */
        private const val SAMPLE_SIZE = 5

        /** CSV, and the extensions an XLIFF file goes by. */
        private val IMPORTABLE_EXTENSIONS = setOf("csv") + XliffTranslationCodec.EXTENSIONS

        /**
         * Each entry of [plan] with the file of [sources] it goes to ([translationFileFor]), apart
         * from the entries no file takes — a namespace the module has no file for in that locale.
         * Those used to be dropped without a word while the summary counted them as imported.
         */
        internal fun routePlan(plan: ImportPlan, sources: List<LocalizationSource>, config: Config): RoutedPlan {
            val synchronizer = KeysSynchronizer()
            val writes = mutableListOf<Write>()
            val unrouted = mutableListOf<ImportEntry>()
            for (entry in plan.entries) {
                val source = translationFileFor(entry.key, entry.locale, sources, config)
                if (source == null) unrouted += entry
                else writes += Write(source, synchronizer.buildFullKey(entry.key, config), entry)
            }
            return RoutedPlan(writes, unrouted)
        }

        /**
         * What the import did — the values of [routed] written, the entries no file took — or, with
         * no [routed] plan, that there was nothing to apply; then the keys and locale columns the
         * file holds that the project does not know.
         */
        internal fun summaryOf(plan: ImportPlan, routed: RoutedPlan?): String = buildString {
            if (routed == null) append(PluginBundle.message("action.import.summary.nothing"))
            else {
                val written = routed.writes.map { it.entry }
                val creations = written.count { it.isCreation }
                append(PluginBundle.message("action.import.summary.applied", written.size, creations, written.size - creations))
                if (routed.unrouted.isNotEmpty()) {
                    append("\n")
                    append(
                        PluginBundle.message(
                            "action.import.summary.unrouted", routed.unrouted.size, sampleOf(routed.unrouted.map { "${it.key} (${it.locale})" })
                        )
                    )
                }
            }
            if (plan.ignoredKeys.isNotEmpty()) {
                append("\n")
                append(PluginBundle.message("action.import.summary.ignored.keys", plan.ignoredKeys.size, sampleOf(plan.ignoredKeys)))
            }
            if (plan.ignoredColumns.isNotEmpty()) {
                append("\n")
                append(
                    PluginBundle.message(
                        "action.import.summary.ignored.columns", plan.ignoredColumns.joinToString(", ")
                    )
                )
            }
        }

        /** The first [SAMPLE_SIZE] of [names], trailing off past them. */
        private fun sampleOf(names: List<String>): String =
            names.take(SAMPLE_SIZE).joinToString(", ") + if (names.size > SAMPLE_SIZE) ", …" else ""
    }
}

/**
 * Mandatory preview shown before any write: Key | Locale | Action | New value.
 * Ignored keys/columns are summarized under the table.
 */
private class ImportPreviewDialog(
    project: Project,
    private val plan: ImportPlan,
) : DialogWrapper(project) {

    init {
        title = PluginBundle.message("action.import.preview.title", plan.entries.size)
        setOKButtonText(PluginBundle.message("action.import.preview.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val columns = arrayOf(
            PluginBundle.message("action.import.preview.column.key"),
            PluginBundle.message("action.import.preview.column.locale"),
            PluginBundle.message("action.import.preview.column.action"),
            PluginBundle.message("action.import.preview.column.value")
        )
        val model = object : DefaultTableModel(columns, 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        plan.entries.forEach { entry ->
            val action =
                if (entry.isCreation) PluginBundle.message("action.import.preview.action.create")
                else PluginBundle.message("action.import.preview.action.update")
            model.addRow(arrayOf(entry.key, entry.locale, action, entry.value))
        }
        val table = JBTable(model)
        table.setShowGrid(false)

        val panel = JPanel(BorderLayout(0, 6))
        panel.add(JScrollPane(table).apply { preferredSize = Dimension(720, 320) }, BorderLayout.CENTER)

        val notes = buildList {
            if (plan.ignoredKeys.isNotEmpty())
                add(PluginBundle.message("action.import.preview.note.keys", plan.ignoredKeys.size))
            if (plan.ignoredColumns.isNotEmpty())
                add(PluginBundle.message("action.import.preview.note.columns", plan.ignoredColumns.joinToString(", ")))
        }
        if (notes.isNotEmpty()) {
            panel.add(JBLabel("<html>${notes.joinToString("<br>")}</html>"), BorderLayout.SOUTH)
        }
        return panel
    }
}

/**
 * The translation file of [locale] for [key] among [sources]: [TranslationFileRouting.fileFor] under
 * [config]'s default namespaces, the routing of *Sync Keys*. Shared by the CSV import, *Fill Missing
 * Translations* and the agents' tools, which write a key into a locale the same way.
 */
internal fun translationFileFor(key: String, locale: String, sources: List<LocalizationSource>, config: Config): LocalizationSource? =
    TranslationFileRouting.fileFor(key, locale, sources, config)
