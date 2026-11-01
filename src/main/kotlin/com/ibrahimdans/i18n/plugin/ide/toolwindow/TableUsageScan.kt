package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * Counts the usages of the Table View's rows in the background, one count at a time.
 *
 * [rows] are the rows to count when the scan starts, [count] does the counting off the EDT, and
 * [onCounted] receives the counted rows on the EDT once the count succeeded.
 *
 * [isScanning] is reset when the count ends, however it ends: resetting it only once a successful
 * count had been applied on the EDT left the action disabled for the session. The task cannot be
 * cancelled by the user, so a cancellation is a write action that interrupted the reads: the scan
 * runs again once.
 */
internal class TableUsageScan(
    private val project: Project,
    private val rows: () -> List<TranslationRow>,
    private val count: (List<TranslationRow>) -> List<TranslationRow>,
    private val onCounted: (List<TranslationRow>) -> Unit,
) {

    @Volatile
    private var scanning: Boolean = false

    /** True while a count is running, so the action does not queue a second one. */
    val isScanning: Boolean get() = scanning

    /** Starts a count, unless one is already running. */
    fun start() = start(retried = false)

    private fun start(retried: Boolean) {
        if (scanning) return
        scanning = true
        val rowsToScan = rows().toList()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, PluginBundle.message("toolwindow.table.scan.progress.title"), false) {
            private var scanned: List<TranslationRow> = emptyList()
            private var interrupted = false

            override fun run(indicator: ProgressIndicator) {
                indicator.text = PluginBundle.message("toolwindow.table.scan.progress.text")
                // Reset here, off the EDT, not in an EDT callback that a modal dialog can hold back.
                try {
                    scanned = count(rowsToScan)
                } finally {
                    scanning = false
                }
            }

            override fun onSuccess() = onCounted(scanned)

            override fun onCancel() {
                interrupted = true
            }

            override fun onFinished() {
                // The toolbar only re-reads the action's state on the next UI event: ask now,
                // or the button stays drawn disabled after the count.
                ActivityTracker.getInstance().inc()
                if (interrupted && !retried) start(retried = true)
            }
        })
    }
}
