package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.util.Alarm

/**
 * Groups a burst of notifications into a single run.
 *
 * Behind an interface so the debounce contract can be exercised without waiting on a
 * clock: a test supplies an implementation that keeps the pending action and runs it
 * on demand.
 */
interface RefreshScheduler {
    /** Replaces any pending action by [action]. */
    fun schedule(action: () -> Unit)
}

/** Production scheduler: runs on the EDT, [delayMs] after the last call. */
class AlarmRefreshScheduler(parent: Disposable, private val delayMs: Int) : RefreshScheduler {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)

    override fun schedule(action: () -> Unit) {
        alarm.cancelAllRequests()
        alarm.addRequest({ action() }, delayMs)
    }
}

/**
 * Decides *when* the tool window reloads once translation files are known to have changed.
 *
 * Two things stand between a change and a reload, because reloading re-reads the whole PSI
 * tree of every translation file:
 *  - [scheduler] groups a burst — one keystroke in the editor produces several events;
 *  - a hidden tool window is not reloaded at all, only marked stale, and catches up when it
 *    is shown again ([onVisibilityChanged]).
 *
 * Deciding *whether* a change is relevant belongs to [TranslationSourceMatcher]; this class
 * knows nothing about files, which is what keeps both testable on their own.
 */
class TranslationChangeWatcher(
    private val isVisible: () -> Boolean,
    private val reload: () -> Unit,
    private val scheduler: RefreshScheduler
) {

    /** A change arrived while the tool window was hidden: what is on screen is stale. */
    private var staleWhileHidden = false

    /** Called when translation files changed on disk. */
    fun onTranslationsChanged() = scheduler.schedule { reloadIfVisible() }

    /** Called when the tool window is shown or hidden; catches up on what was missed. */
    fun onVisibilityChanged() {
        if (staleWhileHidden && isVisible()) reloadIfVisible()
    }

    /** Visible for tests: true when a change was swallowed because nobody was looking. */
    internal fun isStaleWhileHidden(): Boolean = staleWhileHidden

    private fun reloadIfVisible() {
        // Recomputing for a hidden tab is pure wasted work; remember to catch up instead.
        if (!isVisible()) {
            staleWhileHidden = true
            return
        }
        staleWhileHidden = false
        reload()
    }

    companion object {
        /** Long enough to swallow a burst of keystrokes, short enough to feel immediate. */
        const val DEFAULT_DEBOUNCE_MS = 400
    }
}
