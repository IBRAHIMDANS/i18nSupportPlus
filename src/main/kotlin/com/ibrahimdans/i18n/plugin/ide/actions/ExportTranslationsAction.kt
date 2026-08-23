package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import java.nio.charset.StandardCharsets

/**
 * Exports all project translations to a CSV file (column "key" + one column
 * per locale), so they can be handed to a translator and re-imported with
 * [ImportTranslationsAction].
 */
class ExportTranslationsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val scope = chooseModuleScope(project, PluginBundle.message("action.export.title")) ?: return

        val descriptor = FileSaverDescriptor(
            PluginBundle.message("action.export.chooser.title"),
            PluginBundle.message("action.export.chooser.description"),
            "csv"
        )
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as com.intellij.openapi.vfs.VirtualFile?, "translations.csv") ?: return
        val target = wrapper.file

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, PluginBundle.message("action.export.progress.title"), false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = PluginBundle.message("action.export.progress.collecting")
                val translations = TranslationDataLoader.loadAllTranslations(project, scope.config)
                val locales = translations.values.flatMap { it.keys }.distinct().sorted()

                indicator.text = PluginBundle.message("action.export.progress.writing", translations.size)
                val csv = CsvTranslationCodec.encode(locales, translations)
                target.writeText(csv, StandardCharsets.UTF_8)

                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(
                        project,
                        PluginBundle.message(
                            "action.export.done", translations.size, locales.size, target.absolutePath
                        ),
                        PluginBundle.message("action.export.title")
                    )
                }
            }
        })
    }
}
