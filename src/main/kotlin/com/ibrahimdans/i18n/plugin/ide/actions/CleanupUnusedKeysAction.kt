package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.review.ReviewMigration
import com.ibrahimdans.i18n.plugin.review.ReviewSpot
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationFileRouting
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationRow
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginCoroutineScope
import com.ibrahimdans.i18n.plugin.utils.PluginNotifications
import com.ibrahimdans.i18n.plugin.utils.sourcesOf
import com.ibrahimdans.i18n.plugin.utils.deletePropertyAndSeparator
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.table.DefaultTableModel

/**
 * Scans the whole project for translation keys with no code usage and deletes
 * the selected ones from every locale file, in a single WriteCommandAction
 * (one undo step for the entire cleanup).
 *
 * A key is only proposed for deletion when the scan behind the Table View's
 * "Scan Orphans" leaves it at zero — which covers both the text search for the
 * key itself and the guard against keys built at runtime, `t(`role.${'$'}{x}`)`,
 * that no search for a name can find. Better to keep a dead key than delete a
 * live one.
 *
 * That guard used to live here, as a second signal: no PSI reference on the key's
 * property or any of its ancestors. It never protected anything — the reference a
 * template literal carries resolves onto the property's *key literal*, while
 * `ReferencesSearch` on the property compares against the property itself, so the
 * answer was always "no reference". It is now [DynamicKeyUsages], asked once by the
 * scan, so the preview and the table agree on the same set of orphans.
 *
 * In a monorepo (two modules or more), the scan runs module by module: a key is an orphan of a
 * module when that module's code does not use it, whatever another module's code does, and it is
 * deleted from that module's files only.
 */
class CleanupUnusedKeysAction : AnAction(), CompositeKeyResolver<PsiElement> {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        cleanUp(project)
    }

    /**
     * Counts the usages of every key in the background, shows the preview on the EDT, and deletes
     * the keys kept checked. Returns the job running it, launched in the project's [PluginCoroutineScope].
     */
    internal fun cleanUp(project: Project): Job = PluginCoroutineScope.of(project).launch {
        LOG.info("Clean Up Keys with No Reference: started")
        val viewModel = TableViewModel()
        val orphans = withBackgroundProgress(project, PluginBundle.message("action.cleanup.progress.title"), cancellable = false) {
            coroutineToIndicator { indicator ->
                indicator.text = PluginBundle.message("action.cleanup.progress.loading")
                orphans(project, viewModel) { rows ->
                    indicator.text = PluginBundle.message("action.cleanup.progress.counting", rows)
                }
            }
        }
        LOG.info("Clean Up Keys with No Reference: ${orphans.size} keys with no reference found")
        // A key orphaned in several modules is one row per module, told apart by its module.
        val labels = orphans.associateBy { (module, row) -> if (module == null) row.key else "${row.key} — ${module.name}" }

        withContext(Dispatchers.EDT) {
            if (orphans.isEmpty()) {
                PluginNotifications.notify(project, PluginBundle.message("action.cleanup.none"), title = PluginBundle.message("action.cleanup.title"))
                return@withContext
            }
            val dialog = CleanupPreviewDialog(project, labels.map { (label, orphan) -> label to orphan.second.values.size })
            if (!dialog.showAndGet()) return@withContext
            val selected = dialog.selectedKeys().mapNotNull(labels::get)
            if (selected.isEmpty()) return@withContext
            val deletedEntries = selected.groupBy({ it.first }, { it.second.key })
                .entries.sumOf { (module, keys) -> deleteKeys(project, keys, module) }
            LOG.info("Clean Up Keys with No Reference: ${selected.size} keys deleted, $deletedEntries locale entries")
            PluginNotifications.notify(
                project,
                PluginBundle.message("action.cleanup.done", selected.size, deletedEntries),
                title = PluginBundle.message("action.cleanup.title")
            )
        }
    }

    // ── Detection (read actions) ──────────────────────────────────────────────

    /**
     * The keys no code uses, each with the module it was scanned in: every module with a root
     * directory in a monorepo, the whole project (a null module) otherwise. [counting] is told how
     * many rows are about to be counted.
     */
    internal fun orphans(
        project: Project,
        viewModel: TableViewModel = TableViewModel(),
        counting: (Int) -> Unit = {},
    ): List<Pair<ModuleConfig?, TranslationRow>> {
        return ModuleSources.scopes(Settings.getInstance(project).config().modules).flatMap { module ->
            val rows = viewModel.loadRows(project, module)
            counting(rows.size)
            viewModel.countUsages(project, rows, module).filter { it.usageCount == 0 }.map { module to it }
        }
    }

    /**
     * Resolves [key] in the sources that may hold it ([TranslationFileRouting.filesOf]) and returns
     * the property elements (JsonProperty / YAMLKeyValue) holding its value, one per locale where
     * the key exists.
     *
     * A key spelled without a namespace used to be resolved in every file of every locale: with
     * `common` as the default namespace, deleting its `menu.home` also deleted `auth`'s `menu.home`
     * — another key, shown on its own row as `auth:menu.home`.
     *
     * Takes the already-loaded [sources]: resolving them per key turned the scan
     * into O(keys × translation files) full PSI rebuilds.
     */
    internal fun leafProperties(sources: List<LocalizationSource>, key: String, config: Config = Config()): List<PsiElement> {
        val fullKey = KeysSynchronizer().buildFullKey(key, config)
        return TranslationFileRouting.filesOf(key, sources, config)
            .mapNotNull { source ->
                val ref = resolveCompositeKey(fullKey.compositeKey, source) ?: return@mapNotNull null
                val element = ref.element
                if (ref.unresolved.isNotEmpty() || element == null) return@mapNotNull null
                val value = element.value()
                TranslationPsi.propertyOf(value)
            }
    }

    // ── Deletion (single write action) ────────────────────────────────────────

    /**
     * Deletes every locale's property for each of [keys], in one
     * WriteCommandAction — a single undo restores the whole cleanup, the
     * reviews of the deleted keys included ([ReviewMigration]).
     * Pure of any UI (testable); returns the number of deleted locale entries.
     *
     * Runs on the EDT, where its callers are: the preview's confirmation, the AI agents' tool, tests.
     */
    internal fun deleteKeys(project: Project, keys: List<String>, module: ModuleConfig? = null): Int {
        val (properties, reviews) = ReadAction.compute<Pair<List<PsiElement>, List<ReviewSpot>>, RuntimeException> {
            // One source load for the whole batch, not one per key; [module]'s files only.
            val sources = ModuleSources.sourcesOf(project, module)
            val config = Settings.getInstance(project).config()
            val found = keys.flatMap { leafProperties(sources, it, config) }
            found to ReviewMigration.spotsOf(found)
        }
        WriteCommandAction.writeCommandAction(project)
            .withName(PluginBundle.message("action.cleanup.command"))
            .withGlobalUndo()
            .withUndoConfirmationPolicy(UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION)
            .run<RuntimeException> {
                properties.forEach { if (it.isValid) deletePropertyAndSeparator(it) }
                ReviewMigration.remove(project, reviews)
            }
        return properties.size
    }

    private companion object {
        val LOG = Logger.getInstance(CleanupUnusedKeysAction::class.java)
    }
}

/**
 * Mandatory preview: one row per orphan key with a checkbox (all checked by
 * default), the key, and the number of locales it exists in. Unchecking a row
 * keeps the key.
 */
private class CleanupPreviewDialog(
    project: Project,
    orphans: List<Pair<String, Int>>,
) : DialogWrapper(project) {

    private val model = object : DefaultTableModel(
        arrayOf(
            PluginBundle.message("action.cleanup.preview.column.delete"),
            PluginBundle.message("action.cleanup.preview.column.key"),
            PluginBundle.message("action.cleanup.preview.column.locales")
        ),
        0
    ) {
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, column: Int) = column == 0
    }

    init {
        title = PluginBundle.message("action.cleanup.preview.title", orphans.size)
        setOKButtonText(PluginBundle.message("action.cleanup.preview.ok"))
        orphans.sortedBy { it.first }.forEach { (key, localeCount) ->
            model.addRow(arrayOf<Any>(true, key, localeCount.toString()))
        }
        init()
    }

    fun selectedKeys(): List<String> =
        (0 until model.rowCount)
            .filter { model.getValueAt(it, 0) == true }
            .map { model.getValueAt(it, 1) as String }

    override fun createCenterPanel(): JComponent {
        val table = JBTable(model)
        table.setShowGrid(false)
        table.columnModel.getColumn(0).maxWidth = 60
        table.columnModel.getColumn(2).maxWidth = 80

        val panel = JPanel(BorderLayout(0, 6))
        panel.add(JScrollPane(table).apply { preferredSize = Dimension(640, 320) }, BorderLayout.CENTER)
        panel.add(
            JBLabel(PluginBundle.message("action.cleanup.preview.note")),
            BorderLayout.SOUTH
        )
        return panel
    }
}
