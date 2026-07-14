package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec.ImportPlan
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
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

        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("csv")
            .withTitle("Import Translations from CSV")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val text = String(file.contentsToByteArray(), StandardCharsets.UTF_8)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Analyzing CSV…", false) {
            override fun run(indicator: ProgressIndicator) {
                val plan = try {
                    val records = CsvTranslationCodec.parse(text)
                    val existing = TranslationDataLoader.loadAllTranslations(project)
                    val knownLocales = existing.values.flatMap { it.keys }.distinct()
                    CsvTranslationCodec.computeImportPlan(existing, knownLocales, records)
                } catch (ex: IllegalArgumentException) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, ex.message ?: "Malformed CSV file.", "Import Translations")
                    }
                    return
                }

                ApplicationManager.getApplication().invokeLater {
                    if (plan.entries.isEmpty()) {
                        Messages.showInfoMessage(project, summaryOf(plan, applied = false), "Import Translations")
                        return@invokeLater
                    }
                    val dialog = ImportPreviewDialog(project, plan)
                    if (dialog.showAndGet()) {
                        applyPlan(project, plan)
                    }
                }
            }
        })
    }

    /** Applies all planned entries in one WriteCommandAction (single undo step). */
    private fun applyPlan(project: Project, plan: ImportPlan) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Importing translations…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Resolving target files…"
                val viewModel = DialogViewModel(project)
                val synchronizer = KeysSynchronizer()
                val sources = ReadAction.compute<List<LocalizationSource>, RuntimeException> {
                    project.service<LocalizationSourceService>().findAllSources(project)
                }
                val operations = plan.entries.mapNotNull { entry ->
                    val source = findSourceFor(entry.key, entry.locale, sources) ?: return@mapNotNull null
                    Triple(source, synchronizer.buildFullKey(entry.key), entry.value)
                }

                indicator.text = "Writing ${operations.size} values…"
                ApplicationManager.getApplication().invokeAndWait {
                    WriteCommandAction.runWriteCommandAction(project, "Import i18n Translations", null, {
                        for ((source, fullKey, value) in operations) {
                            viewModel.saveTranslation(source, fullKey, value)
                        }
                    })
                    Messages.showInfoMessage(project, summaryOf(plan, applied = true), "Import Translations")
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
        if (applied) append("Imported ${plan.entries.size} value(s): $creations created, $updates updated.")
        else append("Nothing to import: every non-empty CSV cell matches the current translations.")
        if (plan.ignoredKeys.isNotEmpty()) {
            append("\nIgnored ${plan.ignoredKeys.size} unknown key(s): ")
            append(plan.ignoredKeys.take(5).joinToString(", "))
            if (plan.ignoredKeys.size > 5) append(", …")
        }
        if (plan.ignoredColumns.isNotEmpty()) {
            append("\nIgnored unknown locale column(s): ${plan.ignoredColumns.joinToString(", ")}")
        }
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
        title = "Import Translations — Preview (${plan.entries.size} changes)"
        setOKButtonText("Import")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val model = object : DefaultTableModel(arrayOf("Key", "Locale", "Action", "New value"), 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        plan.entries.forEach { entry ->
            model.addRow(arrayOf(entry.key, entry.locale, if (entry.isCreation) "create" else "update", entry.value))
        }
        val table = JBTable(model)
        table.setShowGrid(false)

        val panel = JPanel(BorderLayout(0, 6))
        panel.add(JScrollPane(table).apply { preferredSize = Dimension(720, 320) }, BorderLayout.CENTER)

        val notes = buildList {
            if (plan.ignoredKeys.isNotEmpty())
                add("${plan.ignoredKeys.size} unknown key(s) will be ignored (keys are never created from a CSV).")
            if (plan.ignoredColumns.isNotEmpty())
                add("Unknown locale column(s) ignored: ${plan.ignoredColumns.joinToString(", ")}.")
        }
        if (notes.isNotEmpty()) {
            panel.add(JBLabel("<html>${notes.joinToString("<br>")}</html>"), BorderLayout.SOUTH)
        }
        return panel
    }
}
