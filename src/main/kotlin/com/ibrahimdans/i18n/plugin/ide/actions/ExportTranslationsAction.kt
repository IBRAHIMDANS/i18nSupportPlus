package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginCoroutineScope
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.ibrahimdans.i18n.plugin.utils.XliffTranslationCodec
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.withBackgroundProgress
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Exports the project's translations so they can be handed to a translator and re-imported with
 * [ImportTranslationsAction]: as one CSV file (column "key" + one column per locale), or as XLIFF
 * 1.2 — the format CAT tools read — one `translations.<locale>.xlf` per target locale, written in
 * a folder, each pairing the reference locale's value with the target's. *CSV, missing only* keeps
 * the keys a translator still has to fill: re-importing it writes only the cells they filled.
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
            arrayOf(
                PluginBundle.message("action.export.format.csv"),
                PluginBundle.message("action.export.format.csv.missing"),
                PluginBundle.message("action.export.format.xliff")
            ),
            0,
            null
        )
        when (format) {
            0 -> exportCsv(project, scope, missingOnly = false)
            1 -> exportCsv(project, scope, missingOnly = true)
            2 -> exportXliff(project, scope)
        }
    }

    private fun exportCsv(project: Project, scope: ModuleScope, missingOnly: Boolean) {
        val descriptor = FileSaverDescriptor(
            PluginBundle.message("action.export.chooser.title"),
            PluginBundle.message("action.export.chooser.description"),
            "csv"
        )
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as com.intellij.openapi.vfs.VirtualFile?, if (missingOnly) "translations-missing.csv" else "translations.csv")
            ?: return
        val target = wrapper.file
        run(project) { indicator -> csv(project, scope.config, target, missingOnly, indicator) }
    }

    /** One file per target locale, in a chosen folder: XLIFF pairs a single source with a single target. */
    private fun exportXliff(project: Project, scope: ModuleScope) {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle(PluginBundle.message("action.export.xliff.chooser.title"))
        val folder = FileChooser.chooseFile(descriptor, project, null) ?: return
        run(project) { indicator -> xliff(project, scope.config, File(folder.path), indicator) }
    }

    /** The files an export writes, with their content, and the message shown once they are written. */
    internal data class Export(val files: List<Pair<File, String>>, val message: String)

    companion object {
        /** The CSV of [config]'s translations to [target]; only the keys left to translate if [missingOnly]. */
        internal fun csv(project: Project, config: ModuleConfig?, target: File, missingOnly: Boolean, indicator: ProgressIndicator): Export {
            indicator.text = PluginBundle.message("action.export.progress.collecting")
            val all = TranslationDataLoader.loadAllTranslations(project, config)
            val locales = all.values.flatMap { it.keys }.distinct().sorted()
            val translations = if (missingOnly) incomplete(locales, all) else all
            if (missingOnly && translations.isEmpty()) {
                return Export(emptyList(), PluginBundle.message("action.export.missing.nothing"))
            }

            indicator.text = PluginBundle.message("action.export.progress.writing", translations.size)
            return Export(
                listOf(target to CsvTranslationCodec.encode(locales, translations)),
                PluginBundle.message("action.export.done", translations.size, locales.size, target.absolutePath)
            )
        }

        /** One XLIFF file per target locale of [config], in [directory], each paired with the reference locale. */
        internal fun xliff(project: Project, config: ModuleConfig?, directory: File, indicator: ProgressIndicator): Export {
            indicator.text = PluginBundle.message("action.export.progress.collecting")
            val translations = TranslationDataLoader.loadAllTranslations(project, config)
            val locales = translations.values.flatMap { it.keys }.distinct().sorted()
            val reference = ReferenceLocale.of(config, Settings.getInstance(project).config(), locales) ?: locales.firstOrNull()
                ?: return Export(emptyList(), PluginBundle.message("action.export.xliff.nothing"))
            val targets = locales - reference

            indicator.text = PluginBundle.message("action.export.progress.writing", translations.size)
            val files = targets.map { locale ->
                File(directory, "translations.$locale.xlf") to XliffTranslationCodec.encode(reference, locale, translations)
            }
            val message = if (files.isEmpty()) PluginBundle.message("action.export.xliff.nothing")
            else PluginBundle.message(
                "action.export.xliff.done", translations.size, reference, files.joinToString("\n") { it.first.absolutePath }
            )
            return Export(files, message)
        }

        /**
         * [plan] in the background, cancellable; its files are written only once it is complete, so a
         * cancelled export writes nothing and leaves no file half written. The message is then shown on the EDT.
         */
        internal fun run(project: Project, plan: (ProgressIndicator) -> Export): Job = PluginCoroutineScope.of(project).launch {
            val export = withBackgroundProgress(project, PluginBundle.message("action.export.progress.title"), cancellable = true) {
                coroutineToIndicator { indicator -> plan(indicator) }
            }
            withContext(Dispatchers.IO) {
                export.files.forEach { (file, text) -> file.writeText(text, StandardCharsets.UTF_8) }
            }
            withContext(Dispatchers.EDT) {
                Messages.showInfoMessage(project, export.message, PluginBundle.message("action.export.title"))
            }
        }

        /** The keys at least one of [locales] lacks or leaves empty — what is left to translate. */
        fun incomplete(locales: List<String>, translations: Map<String, Map<String, String>>): Map<String, Map<String, String>> =
            translations.filterValues { values -> locales.any { values[it].isNullOrEmpty() } }
    }
}
