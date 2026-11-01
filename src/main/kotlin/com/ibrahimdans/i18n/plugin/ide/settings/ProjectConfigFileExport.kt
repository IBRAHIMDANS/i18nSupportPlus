package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginNotifications
import com.intellij.notification.NotificationAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
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

    /**
     * What a settings page shows of `i18n-check.json`: the settings it holds, *Edit* when the
     * project has the file — opened once the settings dialog is closed, see [open] — and *Export*.
     */
    fun viewFor(project: Project): ConfigFileView {
        val settings = Settings.getInstance(project)
        return ConfigFileView(
            held = settings.configFileSettings(),
            edit = settings.configFileOrNull()?.let { file -> { open(project, file) } },
            export = { exportSettings(project) },
        )
    }

    /**
     * Writes the project's settings as they apply now, the ones the file already sets included:
     * what the settings page's *Export* button and the setup wizard's *Share* box both write.
     */
    fun exportSettings(project: Project): VirtualFile? = export(project, Settings.getInstance(project).config())

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
                open(project, file)
            })
        )
        return file
    }

    /**
     * Opens [file] in an editor once no modal dialog is showing.
     *
     * The settings dialog and the setup wizard are modal: an editor opened while they show lands
     * behind them, where nobody sees it, so the opening waits for them to close. [later] is the
     * platform's queue; a test hands in its own to run the opening when it chooses.
     */
    fun open(
        project: Project,
        file: VirtualFile,
        later: (Runnable) -> Unit = { ApplicationManager.getApplication().invokeLater(it, ModalityState.nonModal()) },
    ) {
        later(Runnable {
            if (!project.isDisposed && file.isValid) FileEditorManager.getInstance(project).openFile(file, true)
        })
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
