package com.ibrahimdans.i18n.plugin.review.ui

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.review.ReviewEdits
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewSource
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
 * [source] is the value the key is translated from now in the module's reference locale — its own,
 * or a plural form's `other`, see [com.ibrahimdans.i18n.plugin.review.ReviewSources] — recorded with
 * the decision; null or empty when unknown or absent — the key then keeps the source it had.
 */
data class ReviewCell(val key: String, val locale: String, val value: String, val source: String? = null)

/**
 * The review decisions of the tool window and of the review dialog, each one command.
 *
 * However many cells are selected — and however many locale files they span — a decision is one
 * `WriteCommandAction`, so that one Ctrl+Z takes it back whole. Each locale's file is rewritten
 * once, every cell of the locale folded into it, rather than once per cell. The command is global
 * (`withGlobalUndo`): it is made from the tool window, where no editor holds the review files.
 *
 * Each one takes the module's `referenceLocale`, the locale a [ReviewCell.source] is read in: null
 * when unknown, and then no source is recorded.
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
    fun approve(
        project: Project, root: VirtualFile, cells: List<ReviewCell>, defaultNamespace: String = defaultNamespace(project),
        referenceLocale: String? = null
    ): Boolean =
        decide(project, root, cells, ReviewState.APPROVED, "", defaultNamespace, referenceLocale, PluginBundle.message("review.command.approve"))

    /** [cells] marked to review again, by hand: their origin is kept. */
    fun markToReview(
        project: Project, root: VirtualFile, cells: List<ReviewCell>, defaultNamespace: String = defaultNamespace(project),
        referenceLocale: String? = null
    ): Boolean =
        decide(project, root, cells, ReviewState.TO_REVIEW, "", defaultNamespace, referenceLocale, PluginBundle.message("review.command.mark"))

    /**
     * Changes requested on [cells], [text] saying which, [suggestion] the value proposed instead —
     * null or blank when none. Refused — false, nothing written — when [text] is blank: the
     * translator would have nothing to act on.
     */
    fun requestChanges(
        project: Project, root: VirtualFile, cells: List<ReviewCell>, text: String, defaultNamespace: String = defaultNamespace(project),
        referenceLocale: String? = null, suggestion: String? = null
    ): Boolean {
        if (text.isBlank()) return false
        return decide(
            project, root, cells, ReviewState.CHANGES_REQUESTED, text.trim(), defaultNamespace, referenceLocale,
            PluginBundle.message("review.command.request.changes"), suggestion
        )
    }

    /**
     * [suggestion] — what a change request proposed — written as [cell]'s value, and the key marked
     * to review on it: the value changed since the request, someone has to look at it again. One
     * command for both files, so that one Ctrl+Z takes back the value and the mark together. False,
     * nothing written, when the cell has no value, already holds [suggestion], or the translation
     * file cannot be found.
     *
     * The value is written as the tool window's cell edit writes it, in the files of the module
     * whose reviews live under [root] — the whole project when none does.
     */
    fun applySuggestion(
        project: Project, root: VirtualFile, cell: ReviewCell, suggestion: String, defaultNamespace: String = defaultNamespace(project),
        referenceLocale: String? = null
    ): Boolean {
        if (cell.value.isEmpty() || suggestion.isEmpty() || suggestion == cell.value) return false
        val store = ReviewStore.getInstance(project)
        val module = moduleOf(project, root)
        return write(project, PluginBundle.message("review.command.apply.suggestion")) {
            if (!TableViewModel().saveValue(project, cell.key, cell.locale, suggestion, module)) return@write false
            store.update(root, cell.locale) {
                ReviewEdits.decide(
                    it, ReviewKey.fromSpelling(cell.key, defaultNamespace), ReviewState.TO_REVIEW, suggestion,
                    store.reviewer(root), timestamp(store), source = sourceOf(cell, referenceLocale)
                )
            }
        }
    }

    /** [text] added to [cell]'s thread, its decision unchanged. False when [text] is blank or the cell has no value. */
    fun comment(
        project: Project, root: VirtualFile, cell: ReviewCell, text: String, defaultNamespace: String = defaultNamespace(project),
        referenceLocale: String? = null
    ): Boolean {
        if (text.isBlank() || cell.value.isEmpty()) return false
        val store = ReviewStore.getInstance(project)
        return write(project, PluginBundle.message("review.command.comment")) {
            val by = store.reviewer(root)
            val at = timestamp(store)
            store.update(root, cell.locale) {
                ReviewEdits.comment(
                    it, ReviewKey.fromSpelling(cell.key, defaultNamespace), cell.value, by, at, text.trim(), sourceOf(cell, referenceLocale)
                )
            }
        }
    }

    /**
     * The source [cell] is decided against: its [ReviewCell.source] in [referenceLocale]. None for a
     * cell of the reference locale itself, nor when the locale or the source is unknown.
     */
    internal fun sourceOf(cell: ReviewCell, referenceLocale: String?): ReviewSource? {
        val text = cell.source
        if (referenceLocale == null || cell.locale == referenceLocale || text.isNullOrEmpty()) return null
        return ReviewSource.of(referenceLocale, text)
    }

    /** The configured module whose reviews live under [root]; null — the whole project — when none. */
    private fun moduleOf(project: Project, root: VirtualFile): ModuleConfig? =
        Settings.getInstance(project).config().modules.firstOrNull { ReviewLocation.rootOf(project, it) == root }

    private fun decide(
        project: Project, root: VirtualFile, cells: List<ReviewCell>, state: ReviewState, text: String,
        defaultNamespace: String, referenceLocale: String?, commandName: String, suggestion: String? = null,
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
                        ReviewEdits.decide(
                            acc, ReviewKey.fromSpelling(cell.key, defaultNamespace), state, cell.value, by, at, text, sourceOf(cell, referenceLocale), suggestion
                        )
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
