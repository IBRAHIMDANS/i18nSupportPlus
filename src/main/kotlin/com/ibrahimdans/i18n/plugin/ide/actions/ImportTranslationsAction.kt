package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec.ImportPlan
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.charset.StandardCharsets
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.table.DefaultTableModel

/**
 * Imports translations from a CSV file produced by [ExportTranslationsAction]
 * (column "key" + one column per locale). Shows a mandatory preview of what
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

        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("csv")
            .withTitle(PluginBundle.message("action.import.chooser.title"))
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val text = String(file.contentsToByteArray(), StandardCharsets.UTF_8)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, PluginBundle.message("action.import.progress.analyzing"), false) {
            override fun run(indicator: ProgressIndicator) {
                val plan = try {
                    val records = CsvTranslationCodec.parse(text)
                    val existing = TranslationDataLoader.loadAllTranslations(project, scope.config)
                    val knownLocales = existing.values.flatMap { it.keys }.distinct()
                    CsvTranslationCodec.computeImportPlan(existing, knownLocales, records)
                } catch (ex: IllegalArgumentException) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            ex.message ?: PluginBundle.message("action.import.error.malformed"),
                            PluginBundle.message("action.import.title")
                        )
                    }
                    return
                }

                ApplicationManager.getApplication().invokeLater {
                    if (plan.entries.isEmpty()) {
                        Messages.showInfoMessage(
                            project,
                            summaryOf(plan, applied = false),
                            PluginBundle.message("action.import.title")
                        )
                        return@invokeLater
                    }
                    val dialog = ImportPreviewDialog(project, plan)
                    if (dialog.showAndGet()) {
                        applyPlan(project, plan, scope)
                    }
                }
            }
        })
    }

    /** Applies all planned entries in one WriteCommandAction (single undo step). */
    private fun applyPlan(project: Project, plan: ImportPlan, scope: ModuleScope) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, PluginBundle.message("action.import.progress.title"), false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = PluginBundle.message("action.import.progress.resolving")
                val viewModel = DialogViewModel(project)
                val synchronizer = KeysSynchronizer()
                // Scoped to the chosen module: writing against the project-wide source
                // list would route a value into another module's file sharing the same
                // namespace and locale.
                val sources = ReadAction.compute<List<LocalizationSource>, RuntimeException> {
                    TranslationDataLoader.findSources(project, scope.config)
                }
                val operations = plan.entries.mapNotNull { entry ->
                    val source = findSourceFor(entry.key, entry.locale, sources) ?: return@mapNotNull null
                    Triple(source, synchronizer.buildFullKey(entry.key), entry.value)
                }

                indicator.text = PluginBundle.message("action.import.progress.writing", operations.size)
                ApplicationManager.getApplication().invokeAndWait {
                    WriteCommandAction.runWriteCommandAction(
                        project, PluginBundle.message("action.import.command"), null,
                    {
                        for ((source, fullKey, value) in operations) {
                            viewModel.saveTranslation(source, fullKey, value)
                        }
                    })
                    Messages.showInfoMessage(
                        project,
                        summaryOf(plan, applied = true),
                        PluginBundle.message("action.import.title")
                    )
                }
            }
        })
    }

    /**
     * Finds the translation file matching the key's namespace and the target locale
     * (same routing as the Keys Synchronizer).
     */
    private fun findSourceFor(key: String, locale: String, sources: List<LocalizationSource>): LocalizationSource? {
        val colonIdx = key.indexOf(':')
        val namespace = if (colonIdx > 0) key.substring(0, colonIdx) else null
        return sources.firstOrNull { source ->
            TranslationDataLoader.extractLocale(source) == locale &&
                (namespace == null || TranslationDataLoader.extractNamespace(source) == namespace)
        }
    }

    private fun summaryOf(plan: ImportPlan, applied: Boolean): String = buildString {
        val creations = plan.entries.count { it.isCreation }
        val updates = plan.entries.size - creations
        if (applied) append(
            PluginBundle.message("action.import.summary.applied", plan.entries.size, creations, updates)
        ) else append(PluginBundle.message("action.import.summary.nothing"))
        if (plan.ignoredKeys.isNotEmpty()) {
            val sample = plan.ignoredKeys.take(SAMPLE_SIZE).joinToString(", ") +
                if (plan.ignoredKeys.size > SAMPLE_SIZE) ", …" else ""
            append("\n")
            append(PluginBundle.message("action.import.summary.ignored.keys", plan.ignoredKeys.size, sample))
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

    private companion object {
        /** How many of the ignored keys the summary names before trailing off. */
        const val SAMPLE_SIZE = 5
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
