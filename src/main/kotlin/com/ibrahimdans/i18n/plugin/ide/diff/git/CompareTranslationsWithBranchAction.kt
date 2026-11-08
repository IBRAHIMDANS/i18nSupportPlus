package com.ibrahimdans.i18n.plugin.ide.diff.git

import com.ibrahimdans.i18n.plugin.ide.diff.TranslationChanges
import com.ibrahimdans.i18n.plugin.ide.diff.TranslationChangesDialog
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginCoroutineScope
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.withBackgroundProgress
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * *Compare Translations with Branch…*: the keys the working tree adds, removes or changes against a
 * branch — usually the one a pull request targets — and the locales left behind, in the dialog of
 * *Show Translation Changes*.
 */
class CompareTranslationsWithBranchAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val names = BranchTranslationChanges.branchNames(project)
        if (names.isEmpty()) return
        val branch = Messages.showEditableChooseDialog(
            PluginBundle.message("diff.branch.choose.message"),
            PluginBundle.message("diff.branch.choose.title"),
            Messages.getQuestionIcon(),
            names.toTypedArray(),
            BranchTranslationChanges.defaultBase(names, BranchTranslationChanges.trackedBranch(project)),
            null,
        )?.trim()?.takeIf { it in names } ?: return
        run(project, branch)
    }

    internal companion object {

        /**
         * Collects the changes against [branch] in the background, cancellable, then shows them on the EDT:
         * a message when there is none, the changes dialog otherwise. Closing the project cancels it.
         */
        fun run(
            project: Project,
            branch: String,
            collect: (Project, String) -> TranslationChanges = BranchTranslationChanges::collect,
        ): Job = PluginCoroutineScope.of(project).launch {
            val found = withBackgroundProgress(project, PluginBundle.message("diff.branch.progress", branch), cancellable = true) {
                coroutineToIndicator { collect(project, branch) }
            }
            withContext(Dispatchers.EDT) {
                val title = PluginBundle.message("diff.branch.title", branch)
                if (found.changes.isEmpty()) {
                    Messages.showInfoMessage(project, PluginBundle.message("diff.branch.none", branch), title)
                } else {
                    TranslationChangesDialog(project, found, title).show()
                }
            }
        }
    }
}
