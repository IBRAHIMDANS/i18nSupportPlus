package com.ibrahimdans.i18n.plugin.ide.diff

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * *Show Translation Changes*: the keys the working copy adds, removes or changes, per locale, and
 * the locales left behind the reference — what is about to be committed, in translations.
 */
class ShowTranslationChangesAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReadAction.nonBlocking<TranslationChanges> { LocalTranslationChanges.collect(project) }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { found ->
                if (found.changes.isEmpty()) {
                    Messages.showInfoMessage(
                        project, PluginBundle.message("diff.translation.none"), PluginBundle.message("diff.translation.title")
                    )
                } else {
                    TranslationChangesDialog(project, found).show()
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
