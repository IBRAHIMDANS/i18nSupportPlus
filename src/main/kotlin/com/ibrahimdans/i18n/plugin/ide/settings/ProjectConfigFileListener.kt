package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.preview.EditorRefresh
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginNotifications
import com.intellij.notification.NotificationAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent

/**
 * Reads `i18n-check.json` again when one is created, saved, renamed or deleted, and shows the
 * change in the open editors.
 *
 * Any file of that name counts, not only the one at the project's root: telling them apart would
 * cost a lookup per event, and reading the settings again once too often costs nothing.
 */
class ProjectConfigFileListener(private val project: Project) : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed || events.none(::concernsConfigFile)) return
        val settings = Settings.getInstance(project)
        if (settings.configFileChanged()) {
            val file = settings.configFileOrNull()
            PluginNotifications.notify(
                project,
                PluginBundle.message("settings.config.file.read", ProjectConfigFile.PATH),
                actions = *listOfNotNull(file?.let {
                    NotificationAction.createSimpleExpiring(PluginBundle.message("settings.config.file.open")) {
                        if (it.isValid) FileEditorManager.getInstance(project).openFile(it, true)
                    }
                }).toTypedArray()
            )
        }
        EditorRefresh.afterSettingsChange(project)
    }

    private fun concernsConfigFile(event: VFileEvent): Boolean =
        isConfigFile(event.path) || (event is VFilePropertyChangeEvent && isConfigFile(event.oldPath))

    private fun isConfigFile(path: String): Boolean =
        path == ProjectConfigFile.PATH || path.endsWith("/" + ProjectConfigFile.PATH)
}
