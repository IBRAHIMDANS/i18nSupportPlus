package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager

class ToggleFoldingAction : ToggleAction(
    "Show Translations Inline",
    "Toggle display of i18n keys as translated values",
    AllIcons.General.InspectionsEye
) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return Settings.getInstance(project).foldingEnabled
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        Settings.getInstance(project).foldingEnabled = state
        ApplicationManager.getApplication().invokeLater {
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }
}
