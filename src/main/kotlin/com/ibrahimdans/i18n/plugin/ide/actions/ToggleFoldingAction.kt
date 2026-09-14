package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.preview.EditorRefresh
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

/**
 * Text, description and icon all come from `plugin.xml`, which resolves the first two against
 * the plugin's resource bundle. Passing them to the constructor as well would shadow the
 * translation with the English literal for anyone running a localized IDE.
 */
class ToggleFoldingAction : ToggleAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return Settings.getInstance(project).foldingEnabled
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        Settings.getInstance(project).foldingEnabled = state
        // Not a bare daemon restart: the folding pass caches on the PSI stamp, which a
        // settings change does not move — see EditorRefresh.
        EditorRefresh.afterSettingsChange(project)
    }
}
