package com.ibrahimdans.i18n.plugin.ide.whatsnew

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefApp

/**
 * *Help | What's New in i18n Support Plus*: reopens the notes of the running version at any time.
 *
 * Registered in plugin.xml, labelled from the bundle.
 */
class ShowWhatsNewAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        open(project, previousVersion = null)
    }

    companion object {

        /**
         * Shows the notes of the running version, and of every version skipped since
         * [previousVersion], in an editor tab. Falls back to the CHANGELOG in the browser when
         * there is no page to show (resource or version missing), when JCEF is unavailable, or
         * when the editor API is absent from this IDE.
         */
        fun open(project: Project, previousVersion: String?) {
            val html = PluginVersion.current?.let { current ->
                WhatsNewPage.build(
                    previousVersion,
                    current,
                    PluginBundle.message("whatsNew.page.title", current),
                    PluginBundle.message("whatsNew.page.fullChangelog"),
                )
            }
            if (html == null || !openInEditor(project, html)) BrowserUtil.browse(WhatsNewPage.CHANGELOG_URL)
        }

        private fun openInEditor(project: Project, html: String): Boolean = try {
            if (!JBCefApp.isSupported()) {
                false
            } else {
                // Public and neither internal nor deprecated from 2025.1 to 2026.3. Links clicked in
                // the page are sent to the browser by the editor itself.
                HTMLEditorProvider.openEditor(project, PluginBundle.message("whatsNew.page.tab"), html)
                true
            }
        } catch (error: LinkageError) {
            thisLogger().info("HTML editor unavailable, opening the changelog in the browser", error)
            false
        }
    }
}
