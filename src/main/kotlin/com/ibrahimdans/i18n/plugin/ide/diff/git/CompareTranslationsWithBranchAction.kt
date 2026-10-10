package com.ibrahimdans.i18n.plugin.ide.diff.git

import com.ibrahimdans.i18n.plugin.ide.diff.TranslationChanges
import com.ibrahimdans.i18n.plugin.ide.diff.TranslationChangesDialog
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import git4idea.repo.GitRepositoryManager

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

        object : Task.Backgroundable(project, PluginBundle.message("diff.branch.progress", branch), true) {
            private var found: TranslationChanges? = null

            override fun run(indicator: ProgressIndicator) {
                found = BranchTranslationChanges.collect(project, branch)
            }

            override fun onSuccess() {
                val result = found ?: return
                if (result.changes.isEmpty()) {
                    Messages.showInfoMessage(
                        project, PluginBundle.message("diff.branch.none", branch), PluginBundle.message("diff.branch.title", branch)
                    )
                } else {
                    TranslationChangesDialog(project, result, PluginBundle.message("diff.branch.title", branch)).show()
                }
            }
        }.queue()
    }
}
