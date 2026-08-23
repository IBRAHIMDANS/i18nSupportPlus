package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.SimpleToolWindowPanel
import java.awt.Component
import java.awt.Container

/**
 * Counts the code usages of every translation key and fills the table's *Usage* column.
 *
 * The trigger used to be a [javax.swing.JButton] inside the table's own filter bar, next to
 * the namespace combo — a third grammar of action in the same tool window, under the
 * `ActionToolbar` that already held Add, Refresh, Sync and Settings. As a plain button it was
 * also absent from *Find Action* and from any keymap assignment, the same argument that had
 * [SetupWizardAction] added to the *Tools* menu.
 *
 * The action deliberately carries no state: it drives the [TableViewPanel] currently on screen,
 * which is where the scanned rows are displayed and merged. With one tab per module there are
 * several panels, and only the visible one is meant to be scanned.
 *
 * DumbAware so it stays available while the project is still indexing: the scan runs its own
 * background task and reads the PSI from there.
 */
class ScanOrphanKeysAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val panel = targetPanel(e)
        e.presentation.isEnabled = e.project != null && panel != null && !panel.isScanning
    }

    override fun actionPerformed(e: AnActionEvent) {
        targetPanel(e)?.scanOrphans()
    }

    /**
     * The table panel this invocation applies to.
     *
     * The search is bounded by the enclosing [SimpleToolWindowPanel] — the tool window's own
     * shell — rather than climbing to the IDE frame: [update] runs on every toolbar refresh,
     * and walking the whole frame each time would cost far more than the action is worth.
     */
    private fun targetPanel(e: AnActionEvent): TableViewPanel? {
        var ancestor: Component? = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        while (ancestor != null && ancestor !is SimpleToolWindowPanel) ancestor = ancestor.parent
        return ancestor?.let { firstTablePanel(it) }
    }

    /**
     * Breadth-first, preferring the panel actually on screen: a multi-module tool window holds
     * one [TableViewPanel] per module tab and only the selected one is showing. The first found
     * is the fallback, so the action still works before the tool window has been laid out.
     */
    private fun firstTablePanel(root: Component): TableViewPanel? {
        val queue = ArrayDeque<Component>().apply { add(root) }
        var fallback: TableViewPanel? = null
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next is TableViewPanel) {
                if (next.isShowing) return next
                if (fallback == null) fallback = next
            }
            if (next is Container) queue.addAll(next.components)
        }
        return fallback
    }

    companion object {
        /** Must match the `id` the action is registered under in plugin.xml. */
        const val ID = "com.ibrahimdans.i18n.ScanOrphanKeys"
    }
}
