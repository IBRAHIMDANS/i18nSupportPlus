package com.ibrahimdans.i18n.plugin.review.ui

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.review.ReviewEdits
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.time.temporal.ChronoUnit

/**
 * A locale cell a decision is made on: [key] spelled as the tool window spells it ([com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling]),
 * [value] the value the cell holds now — the one the decision is recorded against. An empty
 * [value] is a locale without entry: there is nothing to review, every action skips it.
 */
data class ReviewCell(val key: String, val locale: String, val value: String)

/**
 * The review decisions of the tool window and of the review dialog, each one command.
 *
 * However many cells are selected — and however many locale files they span — a decision is one
 * `WriteCommandAction`, so that one Ctrl+Z takes it back whole. Each locale's file is rewritten
 * once, every cell of the locale folded into it, rather than once per cell. The command is global
 * (`withGlobalUndo`): it is made from the tool window, where no editor holds the review files.
 *
 * Must be called on the EDT, outside a write action.
 */
object ReviewActions {

    /** The directory [moduleConfig]'s reviews hang under, or null when reviews are off for it. */
    fun enabledRoot(project: Project, moduleConfig: ModuleConfig?): VirtualFile? =
        ReviewLocation.rootOf(project, moduleConfig)?.takeIf { ReviewStore.getInstance(project).isEnabled(it) }

    /** The namespace a key spelled without one lives in, as the tool window loads it. */
    fun defaultNamespace(project: Project): String = Settings.getInstance(project).config().defaultNamespaces().first()

    /** [cells] approved. False when nothing was written: no cell with a value, or a file that cannot be read. */
    fun approve(project: Project, root: VirtualFile, cells: List<ReviewCell>, defaultNamespace: String = defaultNamespace(project)): Boolean =
        decide(project, root, cells, ReviewState.APPROVED, "", defaultNamespace, PluginBundle.message("review.command.approve"))

    /** [cells] marked to review again, by hand: their origin is kept. */
    fun markToReview(project: Project, root: VirtualFile, cells: List<ReviewCell>, defaultNamespace: String = defaultNamespace(project)): Boolean =
        decide(project, root, cells, ReviewState.TO_REVIEW, "", defaultNamespace, PluginBundle.message("review.command.mark"))

    /**
     * Changes requested on [cells], [text] saying which. Refused — false, nothing written — when
     * [text] is blank: the translator would have nothing to act on.
     */
    fun requestChanges(
        project: Project, root: VirtualFile, cells: List<ReviewCell>, text: String, defaultNamespace: String = defaultNamespace(project)
    ): Boolean {
        if (text.isBlank()) return false
        return decide(project, root, cells, ReviewState.CHANGES_REQUESTED, text.trim(), defaultNamespace, PluginBundle.message("review.command.request.changes"))
    }

    /** [text] added to [cell]'s thread, its decision unchanged. False when [text] is blank or the cell has no value. */
    fun comment(project: Project, root: VirtualFile, cell: ReviewCell, text: String, defaultNamespace: String = defaultNamespace(project)): Boolean {
        if (text.isBlank() || cell.value.isEmpty()) return false
        val store = ReviewStore.getInstance(project)
        return write(project, PluginBundle.message("review.command.comment")) {
            val by = store.reviewer(root)
            val at = timestamp(store)
            store.update(root, cell.locale) { ReviewEdits.comment(it, ReviewKey.fromSpelling(cell.key, defaultNamespace), cell.value, by, at, text.trim()) }
        }
    }

    private fun decide(
        project: Project, root: VirtualFile, cells: List<ReviewCell>, state: ReviewState, text: String,
        defaultNamespace: String, commandName: String,
    ): Boolean {
        val withValue = cells.filter { it.value.isNotEmpty() }.distinct()
        if (withValue.isEmpty()) return false
        val store = ReviewStore.getInstance(project)
        return write(project, commandName) {
            // One author and one time for the whole decision: it is one gesture.
            val by = store.reviewer(root)
            val at = timestamp(store)
            withValue.groupBy { it.locale }.map { (locale, inLocale) ->
                store.update(root, locale) { reviews ->
                    inLocale.fold(reviews) { acc, cell ->
                        ReviewEdits.decide(acc, ReviewKey.fromSpelling(cell.key, defaultNamespace), state, cell.value, by, at, text)
                    }
                }
            }.all { it }
        }
    }

    private fun write(project: Project, commandName: String, block: () -> Boolean): Boolean {
        var written = false
        WriteCommandAction.writeCommandAction(project)
            .withName(commandName)
            .withGlobalUndo()
            .withUndoConfirmationPolicy(UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION)
            .run<RuntimeException> { written = block() }
        return written
    }

    /** As the store dates its own writes: ISO-8601, to the second. */
    private fun timestamp(store: ReviewStore): String = store.now().truncatedTo(ChronoUnit.SECONDS).toString()
}
