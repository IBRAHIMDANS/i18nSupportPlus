package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginNotifications
import com.intellij.notification.NotificationAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

/**
 * *Export to i18n-check.json*: writes the project's settings to `i18n-check.json` at its root, for
 * the team to commit; the IDE and the command line check then read them from there.
 */
object ProjectConfigFileExport {

    /** Writes [config] to the file, after asking before replacing one; null when nothing was written. */
    fun export(project: Project, config: Config): VirtualFile? {
        val dir = project.guessProjectDir() ?: return null
        val existing = dir.findChild(ProjectConfigFile.PATH)
        if (existing != null && Messages.showYesNoDialog(
                project,
                PluginBundle.message("settings.config.file.export.overwrite.message", ProjectConfigFile.PATH),
                PluginBundle.message("settings.config.file.export"),
                null
            ) != Messages.YES
        ) return null
        val file = write(project, dir, existing, ProjectConfigFile.export(config))
        PluginNotifications.notify(
            project,
            PluginBundle.message("settings.config.file.exported", ProjectConfigFile.PATH),
            actions = *arrayOf(NotificationAction.createSimpleExpiring(PluginBundle.message("settings.config.file.open")) {
                if (file.isValid) FileEditorManager.getInstance(project).openFile(file, true)
            })
        )
        return file
    }

    /** Writes [text] to [existing], or to a new file in [dir], in one undoable command. */
    internal fun write(project: Project, dir: VirtualFile, existing: VirtualFile?, text: String): VirtualFile =
        WriteCommandAction.writeCommandAction(project)
            .withName(PluginBundle.message("settings.config.file.export"))
            .compute<VirtualFile, java.io.IOException> {
                val file = existing ?: dir.createChildData(this, ProjectConfigFile.PATH)
                VfsUtil.saveText(file, text)
                file
            }
}
