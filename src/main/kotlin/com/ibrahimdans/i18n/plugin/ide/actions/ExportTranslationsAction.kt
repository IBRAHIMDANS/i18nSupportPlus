package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.ibrahimdans.i18n.plugin.utils.XliffTranslationCodec
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Exports the project's translations so they can be handed to a translator and re-imported with
 * [ImportTranslationsAction]: as one CSV file (column "key" + one column per locale), or as XLIFF
 * 1.2 — the format CAT tools read — one `translations.<locale>.xlf` per target locale, written in
 * a folder, each pairing the reference locale's value with the target's.
 */
class ExportTranslationsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val scope = chooseModuleScope(project, PluginBundle.message("action.export.title")) ?: return
        val format = Messages.showDialog(
            project,
            PluginBundle.message("action.export.format.prompt"),
            PluginBundle.message("action.export.title"),
            arrayOf(PluginBundle.message("action.export.format.csv"), PluginBundle.message("action.export.format.xliff")),
            0,
            null
        )
        when (format) {
            0 -> exportCsv(project, scope)
            1 -> exportXliff(project, scope)
        }
    }

    private fun exportCsv(project: Project, scope: ModuleScope) {
        val descriptor = FileSaverDescriptor(
            PluginBundle.message("action.export.chooser.title"),
            PluginBundle.message("action.export.chooser.description"),
            "csv"
        )
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as com.intellij.openapi.vfs.VirtualFile?, "translations.csv") ?: return
        val target = wrapper.file

        runExport(project) { indicator ->
            indicator.text = PluginBundle.message("action.export.progress.collecting")
            val translations = TranslationDataLoader.loadAllTranslations(project, scope.config)
            val locales = translations.values.flatMap { it.keys }.distinct().sorted()

            indicator.text = PluginBundle.message("action.export.progress.writing", translations.size)
            target.writeText(CsvTranslationCodec.encode(locales, translations), StandardCharsets.UTF_8)
            PluginBundle.message("action.export.done", translations.size, locales.size, target.absolutePath)
        }
    }

    /** One file per target locale, in a chosen folder: XLIFF pairs a single source with a single target. */
    private fun exportXliff(project: Project, scope: ModuleScope) {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle(PluginBundle.message("action.export.xliff.chooser.title"))
        val folder = FileChooser.chooseFile(descriptor, project, null) ?: return
        val directory = File(folder.path)

        runExport(project) { indicator ->
            indicator.text = PluginBundle.message("action.export.progress.collecting")
            val translations = TranslationDataLoader.loadAllTranslations(project, scope.config)
            val locales = translations.values.flatMap { it.keys }.distinct().sorted()
            val config = Settings.getInstance(project).config()
            val reference = ReferenceLocale.of(scope.config, config, locales) ?: locales.firstOrNull()
                ?: return@runExport PluginBundle.message("action.export.xliff.nothing")
            val targets = locales - reference

            indicator.text = PluginBundle.message("action.export.progress.writing", translations.size)
            val written = targets.map { locale ->
                File(directory, "translations.$locale.xlf").also {
                    it.writeText(XliffTranslationCodec.encode(reference, locale, translations), StandardCharsets.UTF_8)
                }
            }
            if (written.isEmpty()) PluginBundle.message("action.export.xliff.nothing")
            else PluginBundle.message(
                "action.export.xliff.done", translations.size, reference, written.joinToString("\n") { it.absolutePath }
            )
        }
    }

    /** Runs [export] in the background; the message it returns is shown when it is done. */
    private fun runExport(project: Project, export: (ProgressIndicator) -> String) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, PluginBundle.message("action.export.progress.title"), false) {
            override fun run(indicator: ProgressIndicator) {
                val message = export(indicator)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(project, message, PluginBundle.message("action.export.title"))
                }
            }
        })
    }
}
