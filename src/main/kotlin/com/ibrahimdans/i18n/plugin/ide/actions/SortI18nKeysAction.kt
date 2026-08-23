package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.json.psi.JsonFile
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project

/**
 * Sorts the keys of a JSON translation file alphabetically (case-insensitive), recursively
 * into nested objects. The sort itself lives in [JsonKeySorter]; this action exposes it on
 * demand from the editor popup.
 *
 * YAML sorting is intentionally out of scope here (block indentation makes a safe reorder a
 * separate problem).
 */
class SortI18nKeysAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.PSI_FILE) is JsonFile
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) as? JsonFile ?: return
        WriteCommandAction.runWriteCommandAction(
            project,
            PluginBundle.message("action.sort.command"),
            null,
            { sort(file, project) },
            file
        )
    }

    /** Sorts the file in place. Must be called inside a write action. */
    fun sort(file: JsonFile, project: Project) = JsonKeySorter.sort(file, project)
}
