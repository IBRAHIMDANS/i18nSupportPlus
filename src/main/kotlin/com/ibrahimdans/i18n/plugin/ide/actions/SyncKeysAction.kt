package com.ibrahimdans.i18n.plugin.ide.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * Adds the keys missing from a locale to every translation file, after a preview.
 *
 * The work itself lives in [KeysSynchronizer] and was reachable from a single place: the
 * tool window's toolbar. It was therefore absent from the *Tools* menu, from *Find Action*
 * and from any keymap assignment — the argument that had *Run Setup Wizard* added to the
 * menu in the first place. A feature that exists at one point of the interface is a feature
 * half the users never find.
 *
 * The tool window's button now goes through this action rather than calling the
 * synchronizer a second time, so both entry points carry the same label and behaviour.
 *
 * DumbAware so it stays available while the project is still indexing: the synchronizer
 * runs its own background task and reads the PSI from there.
 */
class SyncKeysAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        KeysSynchronizer().sync(project)
    }

    companion object {
        /** Must match the `id` the action is registered under in plugin.xml. */
        const val ID = "com.ibrahimdans.i18n.SyncKeys"
    }
}
