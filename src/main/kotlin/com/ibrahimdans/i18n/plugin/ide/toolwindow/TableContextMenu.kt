package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.actions.FillMissingTranslationsAction
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.actions.MachineFill
import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.review.ui.ReviewActions
import com.ibrahimdans.i18n.plugin.review.ui.ReviewCell
import com.ibrahimdans.i18n.plugin.review.ui.ReviewThreadDialog
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import java.awt.event.MouseEvent
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JTable

/**
 * What the Table View's menus read from the panel and ask it to do. The panel implements it with
 * its own state, which stays private to it.
 */
internal interface TableMenuHost {
    val table: JTable

    /** The view column holding the full key. */
    val keyColumn: Int

    /** The view column holding the usage count. */
    val usageColumn: Int

    /** Every locale the module owns, whether shown or not. */
    val locales: List<String>

    /** The locales laid out as columns, in their order. */
    val shownLocales: List<String>

    /** The locales hidden from the header menu. */
    val hiddenLocales: Set<String>

    /** Every row loaded, whatever the filters keep. */
    val rows: List<TranslationRow>
    val reviews: TableReviews
    val reviewRoot: VirtualFile?

    /** The locale view column [column] displays, or null when it is a leading or the usage column. */
    fun localeAt(column: Int): String?

    /** The text [key] holds now in the reference locale, read from its row: its column may be hidden. */
    fun sourceText(key: String): String?

    /** Shows [locale] when it is hidden, hides it otherwise. */
    fun toggleLocale(locale: String)
    fun refresh()
    fun reloadReviews()
}

/**
 * The two menus of the Table View and the row actions behind them: right-clicking a row offers
 * editing the key, opening its file — Enter and F4 too, see [TableKeyboard] — deleting it when the
 * scan found it unused, the review decisions on the selected cells and machine translation of the
 * column's locale; right-clicking the header picks which locale columns show.
 */
internal class TableContextMenu(
    private val project: Project,
    private val moduleConfig: ModuleConfig?,
    private val viewModel: TableViewModel,
    private val host: TableMenuHost,
) {

    /** The menu of the row under [e]; a right-click inside the selection keeps it. */
    fun showRowMenu(e: MouseEvent) {
        val table = host.table
        val row = table.rowAtPoint(e.point)
        if (row < 0) return
        // A right-click inside the selection keeps it, so that several rows can be deleted at once.
        if (!table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row)
            val column = table.columnAtPoint(e.point)
            if (column >= 0) table.setColumnSelectionInterval(column, column)
        }

        val selected = table.selectedRows.toList().mapNotNull { selectedRow ->
            val key = table.getValueAt(selectedRow, host.keyColumn) as? String ?: return@mapNotNull null
            key to (table.getValueAt(selectedRow, host.usageColumn) as? Int ?: -1)
        }
        val orphanKeys = OrphanKeyDeleter.orphanKeys(selected, viewModel)

        val menu = JPopupMenu()
        menu.add(JMenuItem(PluginBundle.message("toolwindow.table.edit.key")).apply {
            addActionListener { editSelectedRow() }
        })
        menu.add(JMenuItem(PluginBundle.message("toolwindow.table.open.file")).apply {
            addActionListener { openSelectedRowFile() }
        })
        menu.addSeparator()
        // Non-orphan rows of the selection are left out, and the label counts only what is deleted.
        val deleteLabel = if (orphanKeys.size > 1) {
            PluginBundle.message("toolwindow.table.delete.orphans", orphanKeys.size)
        } else {
            PluginBundle.message("toolwindow.table.delete.orphan")
        }
        menu.add(JMenuItem(deleteLabel).apply {
            // Shown even when it does not apply, disabled: hiding it made the entry
            // impossible to discover from a row that had never been scanned.
            isEnabled = orphanKeys.isNotEmpty()
            if (orphanKeys.isEmpty()) toolTipText = PluginBundle.message("toolwindow.table.no.action")
            addActionListener { deleteOrphanKeys(orphanKeys) }
        })
        val reviewItems = reviewMenuItems()
        if (reviewItems.isNotEmpty()) {
            menu.addSeparator()
            reviewItems.forEach(menu::add)
        }
        val column = table.columnAtPoint(e.point)
        val machineItems = listOfNotNull(fillMenuItem(column), retranslateMenuItem(column))
        if (machineItems.isNotEmpty()) {
            menu.addSeparator()
            machineItems.forEach(menu::add)
        }
        menu.show(e.component, e.x, e.y)
    }

    /**
     * Edits the selected row: the in-place editor on a locale column, the translation
     * dialog anywhere else — the key column is read-only, so it has nothing else to offer.
     */
    fun editSelectedRow() {
        val table = host.table
        val row = table.selectedRow
        if (row < 0) return
        val column = table.selectedColumn
        if (host.localeAt(column) != null) {
            table.editCellAt(row, column)
            table.editorComponent?.requestFocusInWindow()
            return
        }
        val key = table.getValueAt(row, host.keyColumn) as? String ?: return
        val dialog = TranslationDialog(project, buildFullKey(key), Mode.EDIT, tabModule = moduleConfig)
        if (dialog.showAndGet()) {
            host.refresh()
        }
    }

    /**
     * Opens the translation file the selected row comes from, at the key itself.
     * The locale is the selected column when one is selected, otherwise the first shown —
     * a row always has a file behind it, and the panel used to give no way to reach it.
     */
    fun openSelectedRowFile() {
        val table = host.table
        val row = table.selectedRow
        if (row < 0) return
        val key = table.getValueAt(row, host.keyColumn) as? String ?: return
        val locale = host.localeAt(table.selectedColumn) ?: host.shownLocales.firstOrNull() ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            val target = ReadAction.compute<Pair<VirtualFile, Int>?, RuntimeException> {
                viewModel.locate(project, key, locale, moduleConfig)
            }
                ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                OpenFileDescriptor(project, target.first, target.second).navigate(true)
            }
        }
    }

    /**
     * Deletes the keys from all matching localization sources using CompositeKeyResolver,
     * in one undoable command, then refreshes the table once the deletion has run.
     */
    private fun deleteOrphanKeys(keyStrings: List<String>) {
        if (keyStrings.size > 1 && Messages.showYesNoDialog(
                project,
                OrphanKeyDeleter.confirmationMessage(keyStrings),
                PluginBundle.message("toolwindow.table.delete.orphans.command"),
                Messages.getWarningIcon()
            ) != Messages.YES
        ) return
        val fullKeys = keyStrings.map(::buildFullKey)
        // Scoped to this panel's module: without it the key is also deleted from
        // another module's file sharing the same namespace.
        // The deletion completes asynchronously (the source lookup runs off the EDT):
        // refreshing right after the call would reload the rows before the key is gone.
        OrphanKeyDeleter(project, moduleConfig).delete(fullKeys, onFinished = host::refresh)
    }

    /**
     * Builds a FullKey from a flat key string, handling optional namespace prefix.
     * Delegates to the synchronizer's parser, which the in-place edit already goes
     * through — the panel used to carry a second copy that kept empty segments.
     */
    private fun buildFullKey(keyString: String): FullKey =
        KeysSynchronizer().buildFullKey(keyString, Settings.getInstance(project).config())

    /**
     * The locale cells of the selection holding a value, the cells a review decision applies to,
     * each with the source its key holds now. The selection is a block of rows and columns (cell
     * selection): every locale column of it counts, the key and usage columns do not.
     */
    fun selectedReviewCells(): List<ReviewCell> {
        val table = host.table
        val columns = table.selectedColumns.filter { host.localeAt(it) != null }
        return table.selectedRows.flatMap { row ->
            val key = table.getValueAt(row, host.keyColumn) as? String ?: return@flatMap emptyList()
            columns.mapNotNull { column ->
                val value = table.getValueAt(row, column) as? String ?: ""
                if (value.isEmpty()) null else ReviewCell(key, host.localeAt(column)!!, value, host.sourceText(key))
            }
        }
    }

    /**
     * *Approve*, *Request Changes…*, *Mark for Review* and *Comments…* on the selected cells, or
     * nothing at all where reviews are off: the menu of a module that never asked for reviews is
     * the one it always had. Each decision is one command whatever the number of cells.
     */
    fun reviewMenuItems(): List<JMenuItem> {
        val reviews = host.reviews
        val root = host.reviewRoot?.takeIf { reviews.enabled } ?: return emptyList()
        val cells = selectedReviewCells()
        val unavailable = PluginBundle.message("review.action.unavailable")
        fun item(label: String, enabled: Boolean, tooltip: String = unavailable, run: () -> Unit) = JMenuItem(label).apply {
            isEnabled = enabled
            if (!enabled) toolTipText = tooltip
            addActionListener { run() }
        }
        val approveLabel = if (cells.size > 1) PluginBundle.message("review.action.approve.many", cells.size)
        else PluginBundle.message("review.action.approve")
        val reference = reviews.referenceLocale
        return listOf(
            item(approveLabel, cells.isNotEmpty()) {
                reviewed(ReviewActions.approve(project, root, cells, referenceLocale = reference), cells)
            },
            item(PluginBundle.message("review.action.request.changes"), cells.isNotEmpty()) { requestChanges(root, cells) },
            item(PluginBundle.message("review.action.mark"), cells.isNotEmpty()) {
                reviewed(ReviewActions.markToReview(project, root, cells, referenceLocale = reference), cells)
            },
            item(
                PluginBundle.message("review.action.comments"), cells.size == 1,
                PluginBundle.message("review.action.comments.unavailable")
            ) { ReviewThreadDialog(project, root, cells.single(), referenceLocale = reference).show() },
        )
    }

    private fun requestChanges(root: VirtualFile, cells: List<ReviewCell>) {
        val text = Messages.showMultilineInputDialog(
            project,
            PluginBundle.message("review.request.changes.prompt"),
            PluginBundle.message("review.request.changes.title"),
            "",
            null,
            object : InputValidator {
                override fun checkInput(inputString: String?): Boolean = !inputString.isNullOrBlank()
                override fun canClose(inputString: String?): Boolean = checkInput(inputString)
            },
        ) ?: return
        reviewed(ReviewActions.requestChanges(project, root, cells, text, referenceLocale = host.reviews.referenceLocale), cells)
    }

    /** After a decision: an error when nothing could be written, the reviews read again otherwise. */
    private fun reviewed(written: Boolean, cells: List<ReviewCell>) {
        if (!written) {
            Messages.showErrorDialog(
                project,
                PluginBundle.message("review.write.failed", cells.map { it.locale }.distinct().joinToString(", ")),
                PluginBundle.message("review.write.failed.title")
            )
        }
        host.reloadReviews()
    }

    /**
     * *Fill Missing Translations in <locale>…* on a locale column, once the project opted in to
     * machine translation: the module and the locale are the table's and the column's, so neither
     * is asked again. Null on another column, on the reference locale itself, or without opt-in.
     */
    fun fillMenuItem(column: Int): JMenuItem? {
        val target = host.localeAt(column) ?: return null
        val provider = FillMissingTranslationsAction.machineTranslator(project) ?: return null
        val config = Settings.getInstance(project).config()
        val source = ReferenceLocale.of(moduleConfig, config, host.locales) ?: return null
        if (source == target) return null
        return JMenuItem(PluginBundle.message("toolwindow.table.fill.missing", target)).apply {
            name = "toolwindow.table.fill.missing"
            addActionListener { MachineFill.run(project, moduleConfig, source, target, provider, onWritten = host::refresh) }
        }
    }

    /**
     * *Retranslate Stale in <locale>…* on a locale column where reviews are on and the project opted
     * in to machine translation: the cells of the column shown as *Source changed*, translated again
     * ([MachineFill.retranslate]). Disabled while the column has none; null on another column, on the
     * reference locale, without reviews or without opt-in.
     */
    fun retranslateMenuItem(column: Int): JMenuItem? {
        val target = host.localeAt(column) ?: return null
        val reviews = host.reviews
        val source = reviews.referenceLocale?.takeIf { reviews.enabled && it != target } ?: return null
        val provider = FillMissingTranslationsAction.machineTranslator(project) ?: return null
        val stale = host.rows.count { reviews.shownOf(it, target) == ShownReview.SOURCE_CHANGED }
        return JMenuItem(PluginBundle.message("toolwindow.table.retranslate.stale", target)).apply {
            name = "toolwindow.table.retranslate.stale"
            isEnabled = stale > 0
            if (stale == 0) toolTipText = PluginBundle.message("toolwindow.table.retranslate.none", target)
            addActionListener { MachineFill.retranslate(project, moduleConfig, source, listOf(target), provider, onWritten = host::refresh) }
        }
    }

    /**
     * Header menu picking which locale columns are laid out. Six locales in a docked panel
     * left no column readable; hiding the ones not being worked on is the way out that does
     * not lose any data.
     */
    fun showLocaleMenu(e: MouseEvent) {
        val locales = host.locales
        if (locales.isEmpty()) return
        val menu = JPopupMenu()
        menu.add(JMenuItem(PluginBundle.message("toolwindow.table.columns.title")).apply { isEnabled = false })
        menu.addSeparator()
        for (locale in locales) {
            menu.add(JCheckBoxMenuItem(locale, locale !in host.hiddenLocales).apply {
                addActionListener { host.toggleLocale(locale) }
            })
        }
        menu.show(e.component, e.x, e.y)
    }
}
