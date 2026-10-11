package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.plugin.ide.actions.ExistingKeyFinder
import com.ibrahimdans.i18n.plugin.ide.actions.FillMissingTranslationsAction
import com.ibrahimdans.i18n.plugin.ide.actions.MachineFill
import com.ibrahimdans.i18n.plugin.ide.dialog.MachineTranslationPreviewDialog
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.translate.TranslationProvider
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * *Internationalize…*: every hard-coded text of the selected files and folders, previewed, then
 * written in one command.
 *
 * 1. In the background, cancellable, file by file: the JS/TS and Vue files of the selection, their
 *    [I18nizePlan] and the reference values each module already holds.
 * 2. On the EDT: [I18nizePreviewDialog], where texts are unchecked and keys edited.
 * 3. When the project has opted in to machine translation and the preview asked for it: the new keys
 *    translated into the other locales of their module, in the background, cancellable, then
 *    previewed the way *Fill Missing Translations* does — a proposal that lost a variable comes
 *    unchecked, and its locale stays empty.
 * 4. Off the EDT again: [I18nizeApply.prepare] on what was accepted; then, on the EDT, its write —
 *    one command, one Ctrl+Z — and a notification summing it up.
 *
 * Nothing is written before the dialog is accepted: cancelling the computation leaves the project as it was.
 */
class I18nizeAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && isApplicable(selection(e))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = selection(e).toList()
        if (selection.isEmpty()) return
        val title = PluginBundle.message("action.i18nize.title")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private var preview: Preview? = null

            override fun run(indicator: ProgressIndicator) {
                preview = ReadAction.nonBlocking<Preview> { compute(project, selection, indicator) }
                    .inSmartMode(project)
                    .wrapProgress(indicator)
                    .executeSynchronously()
            }

            override fun onSuccess() {
                val preview = preview ?: return
                if (preview.plan.files.isEmpty()) {
                    notify(project, PluginBundle.message("action.i18nize.nothing"), NotificationType.INFORMATION)
                    return
                }
                val provider = FillMissingTranslationsAction.machineTranslator(project)
                val model = I18nizePreviewModel(preview.plan, preview.existing, Settings.getInstance(project).config())
                val dialog = I18nizePreviewDialog(project, model, translatable = provider != null)
                if (!dialog.showAndGet()) return
                val plan = dialog.accepted()
                if (provider != null && dialog.translateOthers()) translateThenApply(project, plan, preview.existing, provider)
                else apply(project, plan)
            }
        })
    }

    /**
     * [plan]'s new keys translated in the background, previewed, then written with the reference
     * values in the same command. Cancelling the translation or its preview writes nothing.
     */
    private fun translateThenApply(project: Project, plan: I18nizePlan, existing: Map<PsiFile, Map<String, String>>, provider: TranslationProvider) {
        val title = PluginBundle.message("action.i18nize.title")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private var proposals: List<MachineFill.Proposal>? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = PluginBundle.message("action.i18nize.progress.existing")
                val items = ReadAction.nonBlocking<Map<Pair<String, String>, List<MachineFill.Item>>> { translationItems(project, plan, existing) }
                    .inSmartMode(project)
                    .wrapProgress(indicator)
                    .executeSynchronously()
                proposals = translate(items, provider, indicator)
            }

            override fun onSuccess() {
                val proposals = proposals ?: return
                if (proposals.isEmpty()) return apply(project, plan)
                val locales = proposals.map { it.locale }.distinct().joinToString(", ")
                val dialog = MachineTranslationPreviewDialog(project, locales, proposals)
                if (dialog.showAndGet()) apply(project, plan, dialog.acceptedByLocale())
            }
        })
    }

    /** A plan and, per planned file, the keys its module holds in the reference locale with their values. */
    internal class Preview(val plan: I18nizePlan, val existing: Map<PsiFile, Map<String, String>>)

    internal companion object {

        /** The extensions *Internationalize…* reads texts from: those *Batch Extract* handles, and Vue components. */
        private val EXTENSIONS = setOf("js", "jsx", "ts", "tsx", "vue")

        private fun selection(e: AnActionEvent): Array<VirtualFile> =
            e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
                ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { arrayOf(it) }
                ?: emptyArray()

        /** Whether [files] hold something to internationalize: a folder, or a JS/TS or Vue file. */
        fun isApplicable(files: Array<VirtualFile>): Boolean =
            files.any { it.isDirectory || isSourceFile(it) }

        private fun isSourceFile(file: VirtualFile) = !file.isDirectory && file.extension?.lowercase() in EXTENSIONS

        /**
         * The JS/TS and Vue files of [selection], folders walked recursively, in path order, without
         * duplicates — excluded, ignored and library files (`node_modules`) left out. Read action.
         */
        fun sourceFiles(project: Project, selection: List<VirtualFile>, indicator: ProgressIndicator): List<VirtualFile> {
            val index = ProjectFileIndex.getInstance(project)
            val types = FileTypeManager.getInstance()
            fun skipped(file: VirtualFile) = index.isExcluded(file) || index.isInLibrary(file) || types.isFileIgnored(file)
            val found = linkedSetOf<VirtualFile>()
            selection.forEach { root ->
                VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
                    override fun visitFile(file: VirtualFile): Boolean {
                        indicator.checkCanceled()
                        if (skipped(file)) return false
                        if (isSourceFile(file)) found += file
                        return true
                    }
                })
            }
            return found.sortedBy { it.path }
        }

        /**
         * [selection]'s plan, file by file: [indicator] says which file is read and how far it got,
         * and a cancel stops between two files. Read action, smart mode, off the EDT.
         */
        fun compute(project: Project, selection: List<VirtualFile>, indicator: ProgressIndicator): Preview {
            indicator.isIndeterminate = true
            indicator.text = PluginBundle.message("action.i18nize.progress.collect")
            val psiManager = PsiManager.getInstance(project)
            val files = sourceFiles(project, selection, indicator).mapNotNull { psiManager.findFile(it) }
            indicator.isIndeterminate = false
            val plan = I18nizePlan.of(Progressing(files, indicator))
            indicator.text = PluginBundle.message("action.i18nize.progress.existing")
            return Preview(plan, existingValues(project, plan))
        }

        /**
         * The reference values of each planned file's module, read once per module: reading them
         * walks every translation file of the module.
         */
        private fun existingValues(project: Project, plan: I18nizePlan): Map<PsiFile, Map<String, String>> {
            val config = Settings.getInstance(project).config()
            val byModule = mutableMapOf<Any, Map<String, String>>()
            return plan.files.associate { filePlan ->
                val file = filePlan.file
                file to byModule.getOrPut(moduleOf(project, config, file) ?: NO_MODULE) { ExistingKeyFinder.referenceValues(file) }
            }
        }

        private fun moduleOf(project: Project, config: Config, file: PsiFile): ModuleConfig? =
            file.virtualFile?.takeIf { config.modules.isNotEmpty() }
                ?.let { ModuleSources.owner(config.modules, ModuleSources.FilePath.of(it, project.basePath ?: "")) }

        private val NO_MODULE = Any()

        /**
         * What to send to the engine for [plan], per reference → target locale pair: each key it
         * creates — neither reused nor already in the reference locale ([existing]) — with its text,
         * for every other locale its file's module holds. Read action, smart mode, off the EDT.
         */
        fun translationItems(project: Project, plan: I18nizePlan, existing: Map<PsiFile, Map<String, String>>): Map<Pair<String, String>, List<MachineFill.Item>> {
            val config = Settings.getInstance(project).config()
            val localesByModule = mutableMapOf<Any, List<String>>()
            val items = linkedMapOf<Pair<String, String>, LinkedHashMap<String, MachineFill.Item>>()
            plan.files.forEach { filePlan ->
                val module = moduleOf(project, config, filePlan.file)
                val locales = localesByModule.getOrPut(module ?: NO_MODULE) { TranslationDataLoader.discoverLocales(project, module) }
                val source = ReferenceLocale.of(module, config, locales) ?: return@forEach
                val created = filePlan.entries.filter { !it.reused && existing[filePlan.file]?.containsKey(it.key) != true }
                locales.filter { it != source }.forEach { target ->
                    val pair = items.getOrPut(source to target) { linkedMapOf() }
                    created.forEach { pair.putIfAbsent(it.key, MachineFill.Item(it.key, it.candidate.originalText)) }
                }
            }
            return items.mapValues { it.value.values.toList() }.filterValues { it.isNotEmpty() }
        }

        /** [items] translated by [provider], locale pair after locale pair; null when the user cancelled. */
        fun translate(items: Map<Pair<String, String>, List<MachineFill.Item>>, provider: TranslationProvider, indicator: ProgressIndicator?): List<MachineFill.Proposal>? =
            items.flatMap { (pair, list) ->
                indicator?.text = PluginBundle.message("action.fill.progress.locale", pair.second)
                MachineFill.translate(list, provider, pair.first, pair.second, indicator) ?: return null
            }

        /**
         * Writes [plan], prepared off the EDT, with [translations] — locale → key → value — in the
         * other locales, then sums up what was written.
         */
        private fun apply(project: Project, plan: I18nizePlan, translations: Map<String, Map<String, String>> = emptyMap()) {
            if (plan.files.isEmpty()) return
            ReadAction.nonBlocking<I18nizeApply> { I18nizeApply.prepare(plan, translations) }
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.nonModal()) { prepared ->
                    val outcome = prepared.write(PluginBundle.message("action.i18nize.title"))
                    val summary = Summary.of(plan, prepared.skipped, outcome)
                    notify(project, summary.message(), if (summary.skipped > 0) NotificationType.WARNING else NotificationType.INFORMATION)
                }
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        private fun notify(project: Project, content: String, type: NotificationType) {
            if (ApplicationManager.getApplication().isUnitTestMode) return
            NotificationGroupManager.getInstance()
                .getNotificationGroup("i18n Support Plus")
                .createNotification(PluginBundle.message("action.i18nize.title"), content, type)
                .notify(project)
        }
    }

    /**
     * What a write did, as the notification says it: [created] new keys, [reused] existing ones
     * (each key counted once), [files] code files changed, [skipped] texts left for lack of a
     * reference translation file.
     */
    internal data class Summary(val created: Int, val reused: Int, val files: Int, val skipped: Int, val createdFiles: Int) {

        fun message(): String = buildString {
            append(PluginBundle.message("action.i18nize.summary", created, reused, files))
            if (createdFiles > 0) append(' ').append(PluginBundle.message("action.i18nize.summary.files", createdFiles))
            if (skipped > 0) append(' ').append(PluginBundle.message("action.i18nize.summary.skipped", skipped))
        }

        companion object {
            fun of(plan: I18nizePlan, skipped: List<I18nizePlan.Entry>, outcome: I18nizeApply.Outcome): Summary {
                val left = skipped.toSet()
                val written = plan.files.map { file -> file to file.entries.filterNot { it in left } }.filter { it.second.isNotEmpty() }
                val entries = written.flatMap { it.second }
                val (reused, created) = entries.partition { it.reused }
                return Summary(
                    created = created.map { it.namespace to it.key }.distinct().size,
                    reused = reused.map { it.namespace to it.key }.distinct().size,
                    files = if (outcome.replaced == 0) 0 else written.size,
                    skipped = skipped.size,
                    createdFiles = outcome.createdFiles.size
                )
            }
        }
    }

    /**
     * [files], reporting on [indicator] each one handed out: [I18nizePlan.of] walks its list once,
     * in order, so the file it is given is the file it plans. A cancel is noticed between two files.
     *
     * The plan is computed over all the files at once — its key naming spans the whole module, so
     * that two files never give one key two texts — which leaves its iteration as the only place
     * to see it progress without changing it.
     */
    private class Progressing(private val files: List<PsiFile>, private val indicator: ProgressIndicator) : AbstractList<PsiFile>() {
        override val size: Int get() = files.size

        override fun get(index: Int): PsiFile {
            indicator.checkCanceled()
            val file = files[index]
            indicator.fraction = index.toDouble() / files.size
            indicator.text = PluginBundle.message("action.i18nize.progress.file", index + 1, files.size)
            indicator.text2 = file.virtualFile?.presentableUrl ?: file.name
            return file
        }
    }
}
