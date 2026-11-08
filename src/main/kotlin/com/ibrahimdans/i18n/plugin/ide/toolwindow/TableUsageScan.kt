package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginCoroutineScope
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.application.EDT
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Counts the usages of the Table View's rows in the background, one count at a time.
 *
 * [rows] are the rows to count when the scan starts, [count] does the counting off the EDT, and
 * [onCounted] receives the counted rows on the EDT once the count succeeded.
 *
 * [isScanning] is reset when the count ends, however it ends: resetting it only once a successful
 * count had been applied on the EDT left the action disabled for the session. The user can cancel
 * the count from its progress, or the project's closing does: nothing is applied then. A count
 * interrupted while its job still runs is a write action that interrupted the reads: it runs
 * again once, on the rows of that moment.
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

    /** Starts a count, from the EDT, unless one is already running. Its job, or null when one already runs. */
    fun start(): Job? {
        if (scanning) return null
        scanning = true
        val rowsToScan = rows().toList()

        val job = PluginCoroutineScope.of(project).launch {
            val scanned = countInBackground(rowsToScan)
                ?: countInBackground(withContext(Dispatchers.EDT) { rows().toList() })
            // Reset here, off the EDT, not in an EDT step that a modal dialog can hold back.
            scanning = false
            if (scanned != null) withContext(Dispatchers.EDT) { onCounted(scanned) }
        }
        // However the job ends, cancelled before it started included.
        job.invokeOnCompletion {
            scanning = false
            // The toolbar only re-reads the action's state on the next UI event: ask now,
            // or the button stays drawn disabled after the count.
            ActivityTracker.getInstance().inc()
        }
        return job
    }

    /**
     * [rowsToScan] counted under a cancellable progress, or null when a cancellation interrupted the
     * count while the job still runs. A cancelled job throws instead: no second count, nothing applied.
     */
    private suspend fun countInBackground(rowsToScan: List<TranslationRow>): List<TranslationRow>? {
        val scanned = withBackgroundProgress(project, PluginBundle.message("toolwindow.table.scan.progress.title"), cancellable = true) {
            coroutineToIndicator { indicator ->
                indicator.text = PluginBundle.message("toolwindow.table.scan.progress.text")
                countOrInterrupted(rowsToScan)
            }
        }
        currentCoroutineContext().ensureActive()
        return scanned
    }

    @Suppress("SwallowedException")
    private fun countOrInterrupted(rowsToScan: List<TranslationRow>): List<TranslationRow>? = try {
        count(rowsToScan)
    } catch (_: ProcessCanceledException) {
        // Told apart by countInBackground: the job is cancelled when the user or the project stopped it.
        null
    }
}
