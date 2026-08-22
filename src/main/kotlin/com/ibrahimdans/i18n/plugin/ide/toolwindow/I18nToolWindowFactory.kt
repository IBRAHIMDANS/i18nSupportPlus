package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory

/**
 * Factory for the I18n tool window.
 * Registered in plugin.xml as a bottom tool window.
 */
class I18nToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = I18nToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        watchTranslationFiles(project, toolWindow, panel, content)
    }

    /**
     * Keeps the tool window in step with the translation files on disk: editing `common.json`
     * in the editor used to leave the tree, the table and the statistics showing the previous
     * values until the user clicked *Refresh*, with nothing saying they were stale.
     *
     * Subscribed programmatically rather than declared as a `<listener>` in plugin.xml: the
     * subscription is bound to [content], so it lives exactly as long as the tool window it
     * reloads, and it needs the panel instance anyway.
     */
    private fun watchTranslationFiles(
        project: Project,
        toolWindow: ToolWindow,
        panel: I18nToolWindowPanel,
        content: Content
    ) {
        val matcher = TranslationSourceMatcher(project)
        // The panel reloaded during its own init, so the scan is cached and this is free.
        matcher.rememberDisplayedSources()

        val watcher = TranslationChangeWatcher(
            isVisible = { toolWindow.isVisible },
            reload = {
                panel.refresh()
                // Cheap here too — refresh() just warmed the scan cache — and it is what
                // lets the next content change be recognised, new files included.
                matcher.rememberDisplayedSources()
            },
            scheduler = AlarmRefreshScheduler(content, TranslationChangeWatcher.DEFAULT_DEBOUNCE_MS)
        )

        val connection = project.messageBus.connect(content)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                if (matcher.matchesAny(events.toList())) watcher.onTranslationsChanged()
            }
        })
        connection.subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) = watcher.onVisibilityChanged()
        })
    }
}
