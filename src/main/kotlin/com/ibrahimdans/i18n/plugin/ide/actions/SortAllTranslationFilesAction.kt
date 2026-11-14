package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginNotifications
import com.ibrahimdans.i18n.plugin.utils.ownerOf
import com.intellij.json.psi.JsonFile
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.util.concurrent.Callable

/**
 * Sorts every JSON translation file of the project in the *Key order* of the settings, in one go.
 *
 * *Sort i18n Keys* sorts the file open in the editor only: with one large file per locale, keeping
 * them sorted meant opening each of them. Re-sorting after every new key keeps them sorted but costs
 * a pause on each key in a large file; this action lets the user leave that option off and sort
 * everything when it suits them, from any file.
 *
 * The files are listed and checked under a cancellable progress bar; only those out of order are
 * rewritten, all of them in a single undoable command. JSON only, like *Sort i18n Keys*; a Flutter ARB
 * catalogue is sorted the way [JsonKeySorter] sorts one, each `@key` block kept after its key and the
 * `@@…` entries first. Files of a module put aside (*Active* unchecked) are left alone.
 */
class SortAllTranslationFilesAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        // In the editor's i18n submenu, only where the other entries show: code or a translation
        // file of a project that has translations. Everywhere else (Tools menu, Find Action), always.
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = e.place != ActionPlaces.EDITOR_POPUP ||
            (file != null && I18nEditorScope.isCodeOrTranslation(file) && I18nEditorScope.hasSources(file))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val title = PluginBundle.message("action.sort.all.title")
        val unsorted = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<VirtualFile>, RuntimeException>(
            { ReadAction.nonBlocking(Callable { unsortedFiles(project) }).executeSynchronously() },
            title,
            true,
            project
        )
        val sorted = sortAll(project, unsorted)
        val content = if (sorted.isEmpty()) PluginBundle.message("action.sort.all.none")
        else PluginBundle.message("action.sort.all.done", sorted.size, sorted.joinToString("<br/>") { it.path.removePrefix("${project.basePath}/") })
        PluginNotifications.notify(project, content, NotificationType.INFORMATION, title)
    }

    internal companion object {

        /**
         * The JSON translation files of [project] whose keys are out of the *Key order*, at any
         * depth. Needs a read action; checks for cancellation between files.
         */
        fun unsortedFiles(project: Project): List<VirtualFile> =
            translationFiles(project).filter { file ->
                ProgressManager.checkCanceled()
                !JsonKeySorter.isSorted(file, project)
            }.mapNotNull { it.virtualFile }

        /** The JSON translation files of [project], ARB catalogues included, but no file of a module put aside. Needs a read action. */
        fun translationFiles(project: Project): List<JsonFile> =
            project.service<LocalizationSourceService>().findAllSources(project)
                .asSequence()
                .filter { it.host == null }
                .mapNotNull { it.tree?.value()?.containingFile as? JsonFile }
                .filter { file -> file.virtualFile?.let { isSortable(project, it) } == true }
                .distinctBy { it.virtualFile }
                .toList()

        private fun isSortable(project: Project, file: VirtualFile): Boolean {
            val owner = ModuleSources.ownerOf(project, file)
            return owner == null || owner.enabled
        }

        /**
         * Sorts [files] in a single command, so one undo restores them all; the files sorted. A file
         * that became read-only or stopped being JSON since it was listed is skipped.
         */
        fun sortAll(project: Project, files: List<VirtualFile>): List<VirtualFile> {
            if (files.isEmpty()) return emptyList()
            val status = ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(files)
            val writable = files.filter { it.isValid && it !in status.readonlyFiles }
            val psiFiles = writable.mapNotNull { PsiManager.getInstance(project).findFile(it) as? JsonFile }
            if (psiFiles.isEmpty()) return emptyList()
            WriteCommandAction.writeCommandAction(project, *psiFiles.toTypedArray())
                .withName(PluginBundle.message("action.sort.all.title"))
                .run<RuntimeException> { psiFiles.forEach { JsonKeySorter.sort(it, project) } }
            return psiFiles.mapNotNull { it.virtualFile }
        }
    }
}
