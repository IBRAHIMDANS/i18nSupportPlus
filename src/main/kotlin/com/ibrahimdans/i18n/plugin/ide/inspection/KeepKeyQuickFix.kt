package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.ide.settings.ProjectConfigFile
import com.ibrahimdans.i18n.plugin.ide.settings.ProjectConfigFileExport
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginNotifications
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Adds [key] to the project's [KeepList], for a key the code uses in a way no search can see —
 * received from an API, stored elsewhere. The rule can be widened to a prefix or a glob later,
 * in the settings.
 *
 * When `i18n-check.json` sets the list, the stored setting does not apply: the fix says so and
 * opens the file, where the team's list is changed. It used to write the stored setting, with no
 * effect at all.
 */
internal class KeepKeyQuickFix(private val key: String) : LocalQuickFix {

    override fun getName(): String = PluginBundle.message("inspection.unused.fix.keep", key)
    override fun getFamilyName(): String = PluginBundle.message("inspection.unused.fix.keep.family")
    override fun startInWriteAction(): Boolean = false

    // The fix changes the settings, not the file: there is nothing to show in a preview.
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val settings = Settings.getInstance(project)
        val config = settings.config()
        val rules = KeepList.parse(config.keptKeys)
        if (key in rules) return
        val file = settings.configFileOrNull()
        if (file != null && KEPT_KEYS in settings.configFileSettings()) {
            PluginNotifications.notify(
                project,
                PluginBundle.message("inspection.unused.fix.keep.in.file", key, KEPT_KEYS, ProjectConfigFile.PATH),
                NotificationType.WARNING,
            )
            ProjectConfigFileExport.open(project, file)
            return
        }
        settings.setConfig(config.copy(keptKeys = (rules + key).joinToString(", ")))
        descriptor.psiElement?.containingFile?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
    }

    private companion object {
        /** The setting's name in `i18n-check.json`. */
        const val KEPT_KEYS = "keptKeys"
    }
}
