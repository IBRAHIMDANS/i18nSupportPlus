package com.ibrahimdans.i18n.plugin.review.ui

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewMigration
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginCoroutineScope
import com.ibrahimdans.i18n.plugin.utils.PluginNotifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.table.DefaultTableModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * *Clean Up Orphan Reviews*: lists the reviews whose key no longer holds a value in their locale —
 * a key renamed or deleted outside the plugin, by hand or by a merge — and deletes them once the
 * person has seen the list. Never on its own: [ReviewMigration.orphans] finds nothing when the
 * translations of a module load empty or fail to load, rather than offer every review for deletion.
 *
 * In the *Tools → i18n* menu, next to *Cleanup Unused Keys*: like it, it covers every module of the
 * project at once, which the tool window — showing one module — does not.
 */
class CleanupReviewsAction : AnAction() {

    /** Orphans found under one module's reviews folder. */
    internal data class Found(val root: VirtualFile, val module: String?, val orphans: List<ReviewMigration.Orphan>)

    /** What the scan found, and the modules whose translations could not be loaded, left alone. */
    internal data class Scan(val found: List<Found>, val skipped: List<String>) {
        val count: Int get() = found.sumOf { it.orphans.size }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        run(project)
    }

    internal companion object {

        private val LOG = Logger.getInstance(CleanupReviewsAction::class.java)

        /**
         * [scan] in the background, then the notification or the preview and the deletion on the EDT.
         * Its job, cancelled with the progress: cancelling the scan opens nothing and deletes nothing.
         */
        fun run(project: Project, scan: (Project) -> Scan = ::scan): Job = PluginCoroutineScope.of(project).launch {
            val title = PluginBundle.message("action.cleanup.reviews.title")
            val found = withBackgroundProgress(project, title, cancellable = true) {
                coroutineToIndicator { scan(project) }
            }
            withContext(Dispatchers.EDT) {
                val skipped = if (found.skipped.isEmpty()) ""
                    else "\n\n" + PluginBundle.message("action.cleanup.reviews.skipped", found.skipped.joinToString(", "))
                if (found.count == 0) {
                    // A notification is HTML: the blank line of the dialog note becomes a <br/>.
                    PluginNotifications.notify(project, PluginBundle.message("action.cleanup.reviews.none") + skipped.replace("\n", "<br/>"), title = title)
                    return@withContext
                }
                if (!PreviewDialog(project, found, skipped).showAndGet()) return@withContext
                val deleted = delete(project, found)
                PluginNotifications.notify(project, PluginBundle.message("action.cleanup.reviews.done", deleted), title = title)
            }
        }

        /** The orphan reviews of every module with reviews on. Off the EDT. */
        fun scan(project: Project): Scan {
            val config = Settings.getInstance(project).config()
            val modules: List<ModuleConfig?> = config.modules.ifEmpty { listOf(null) }
            val store = ReviewStore.getInstance(project)
            val found = mutableListOf<Found>()
            val skipped = mutableListOf<String>()
            val seen = mutableSetOf<VirtualFile>()
            for (module in modules) {
                val root = ReviewActions.enabledRoot(project, module) ?: continue
                if (!seen.add(root)) continue
                val locales = ReadAction.compute<List<String>, RuntimeException> {
                    ReviewLocation.directoryOf(root)?.children.orEmpty()
                        .filter { !it.isDirectory && it.extension == "json" }
                        .map { it.nameWithoutExtension }
                }
                val reviews = locales.associateWith { store.reviews(root, it) }.filterValues { it.isNotEmpty() }
                if (reviews.isEmpty()) continue
                val translations = try {
                    TranslationDataLoader.loadAllTranslations(project, module)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Clean Up Orphan Reviews: translations of ${module?.name ?: root.path} could not be loaded", e)
                    emptyMap()
                }
                // The guard: an empty load deletes nothing, it is reported instead.
                if (translations.isEmpty()) {
                    skipped += module?.name ?: root.name
                    continue
                }
                val orphans = ReviewMigration.orphans(reviews, translations, config.defaultNamespaces())
                if (orphans.isNotEmpty()) found += Found(root, module?.name, orphans)
            }
            return Scan(found, skipped)
        }

        /**
         * Deletes the reviews of [scan], one command for all of them (one Ctrl+Z restores them).
         * On the EDT. Returns how many were deleted.
         */
        fun delete(project: Project, scan: Scan): Int {
            val store = ReviewStore.getInstance(project)
            var deleted = 0
            WriteCommandAction.writeCommandAction(project)
                .withName(PluginBundle.message("action.cleanup.reviews.command"))
                .withGlobalUndo()
                .withUndoConfirmationPolicy(UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION)
                .run<RuntimeException> {
                    scan.found.forEach { found ->
                        found.orphans.groupBy { it.locale }.forEach { (locale, orphans) ->
                            val keys = orphans.map { it.key }.toSet()
                            if (store.update(found.root, locale) { reviews -> reviews - keys }) deleted += keys.size
                        }
                    }
                }
            return deleted
        }
    }

    /** The mandatory preview: every review the cleanup deletes — module, locale, key, state. */
    private class PreviewDialog(project: Project, private val scan: Scan, private val note: String) : DialogWrapper(project) {

        init {
            title = PluginBundle.message("action.cleanup.reviews.preview.title", scan.count)
            setOKButtonText(PluginBundle.message("action.cleanup.reviews.preview.ok", scan.count))
            init()
        }

        override fun createCenterPanel(): JComponent {
            val model = object : DefaultTableModel(
                arrayOf(
                    PluginBundle.message("action.cleanup.reviews.preview.column.module"),
                    PluginBundle.message("action.cleanup.reviews.preview.column.locale"),
                    PluginBundle.message("action.cleanup.reviews.preview.column.key"),
                    PluginBundle.message("action.cleanup.reviews.preview.column.state"),
                ),
                0
            ) {
                override fun isCellEditable(row: Int, column: Int) = false
            }
            scan.found.forEach { found ->
                found.orphans.forEach { model.addRow(arrayOf<Any>(found.module ?: "", it.locale, it.key.stored, it.review.state.id)) }
            }
            val panel = JPanel(BorderLayout(0, 6))
            panel.add(JScrollPane(JBTable(model)).apply { preferredSize = Dimension(640, 320) }, BorderLayout.CENTER)
            panel.add(JBLabel(PluginBundle.message("action.cleanup.reviews.preview.note") + note.replace('\n', ' ')), BorderLayout.SOUTH)
            return panel
        }
    }
}
