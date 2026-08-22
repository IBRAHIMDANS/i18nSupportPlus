package com.ibrahimdans.i18n.plugin.ide.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the two policies standing between a change and a reload: the burst is grouped,
 * and a hidden tool window is never reloaded.
 *
 * No platform involved — the watcher deliberately knows nothing about files, and the
 * scheduler is behind an interface so the debounce contract can be checked without
 * waiting on a clock.
 */
class TranslationChangeWatcherTest {

    /** Keeps the pending action instead of running it, the way the real Alarm defers it. */
    private class PendingScheduler : RefreshScheduler {
        var scheduleCount = 0
        private var pending: (() -> Unit)? = null

        override fun schedule(action: () -> Unit) {
            scheduleCount++
            // Replaces the previous one: this is the debounce.
            pending = action
        }

        fun hasPending() = pending != null

        fun runPending() {
            val action = pending ?: return
            pending = null
            action()
        }
    }

    private val scheduler = PendingScheduler()
    private var reloads = 0
    private var visible = true

    private fun watcher() = TranslationChangeWatcher(
        isVisible = { visible },
        reload = { reloads++ },
        scheduler = scheduler
    )

    @Test
    fun burstOfChangesProducesSingleReload() {
        val watcher = watcher()

        repeat(5) { watcher.onTranslationsChanged() }
        assertEquals(0, reloads, "Nothing runs before the delay elapses")

        scheduler.runPending()

        assertEquals(5, scheduler.scheduleCount, "Every change reschedules")
        assertEquals(1, reloads, "…but only the last one survives")
        assertFalse(scheduler.hasPending(), "Nothing left pending")
    }

    @Test
    fun changeWhileVisibleReloads() {
        val watcher = watcher()

        watcher.onTranslationsChanged()
        scheduler.runPending()

        assertEquals(1, reloads)
        assertFalse(watcher.isStaleWhileHidden(), "Nothing was missed")
    }

    @Test
    fun changeWhileHiddenReloadsNothingAndIsRemembered() {
        visible = false
        val watcher = watcher()

        watcher.onTranslationsChanged()
        scheduler.runPending()

        assertEquals(0, reloads, "Recomputing for a hidden tab is wasted work")
        assertTrue(watcher.isStaleWhileHidden(), "What is on screen is now stale")
    }

    @Test
    fun reopeningCatchesUpOnWhatWasMissed() {
        visible = false
        val watcher = watcher()
        watcher.onTranslationsChanged()
        scheduler.runPending()

        visible = true
        watcher.onVisibilityChanged()

        assertEquals(1, reloads, "The missed change is applied on reopening")
        assertFalse(watcher.isStaleWhileHidden(), "…and only once")
    }

    @Test
    fun reopeningWithoutMissedChangeReloadsNothing() {
        visible = false
        val watcher = watcher()

        visible = true
        watcher.onVisibilityChanged()

        assertEquals(0, reloads, "Showing the tool window is not a reason to reload")
    }

    @Test
    fun hidingNeverReloads() {
        val watcher = watcher()
        watcher.onTranslationsChanged()
        scheduler.runPending()
        assertEquals(1, reloads)

        visible = false
        watcher.onVisibilityChanged()

        assertEquals(1, reloads, "stateChanged also fires when hiding")
    }
}
