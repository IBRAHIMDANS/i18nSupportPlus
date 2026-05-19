package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.settings.SetupWizardDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * Manually opens the Setup Wizard. The wizard otherwise only appears
 * automatically on first launch, leaving no way to reopen it once dismissed
 * or when a partial configuration already exists.
 *
 * DumbAware so it stays available while the project is still indexing —
 * the action touches neither indexes nor PSI.
 */
class SetupWizardAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Explicit launch: clear the dismissed flag so the wizard behaves consistently afterwards.
        Settings.getInstance(project).wizardDismissed = false
        SetupWizardDialog(project).show()
    }
}
