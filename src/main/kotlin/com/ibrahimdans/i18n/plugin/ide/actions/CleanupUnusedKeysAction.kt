package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.deletePropertyAndSeparator
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import org.jetbrains.yaml.psi.YAMLKeyValue
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
 * A key is only proposed for deletion when two independent signals agree:
 *  1. the text scan finds zero occurrences of the key (full and bare form) —
 *     the same scan as the Table View's "Scan Orphans";
 *  2. no PSI reference points at the key's property **or any of its ancestors**.
 *     The ancestor check is the dynamic-key guard: `t(`role.${'$'}{x}`)` resolves to
 *     the parent `role` object, so every child of a dynamically-accessed parent
 *     is protected. Better to keep a dead key than delete a live one.
 */
class CleanupUnusedKeysAction : AnAction(), CompositeKeyResolver<PsiElement> {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val viewModel = TableViewModel()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scanning for unused i18n keys…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Loading translations…"
                val rows = viewModel.loadRows(project)

                indicator.text = "Counting usages for ${rows.size} keys…"
                val candidates = viewModel.countUsages(project, rows).filter { it.usageCount == 0 }

                indicator.text = "Checking references on ${candidates.size} candidates…"
                // Load the sources ONCE. findAllSources rebuilds the PSI tree of every
                // translation file, so calling it per key made the scan O(keys × files):
                // a few hundred dead keys over a few dozen locale files froze the IDE.
                val sources = ReadAction.compute<List<LocalizationSource>, RuntimeException> {
                    project.service<LocalizationSourceService>().findAllSources(project)
                }
                val orphans = candidates.filter { row ->
                    ReadAction.compute<Boolean, RuntimeException> { !hasPsiReferences(sources, row.key) }
                }

                ApplicationManager.getApplication().invokeLater {
                    if (orphans.isEmpty()) {
                        Messages.showInfoMessage(project, "No unused key found. Nothing to clean up.", "Cleanup Unused Keys")
                        return@invokeLater
                    }
                    val dialog = CleanupPreviewDialog(project, orphans.map { it.key to it.values.size })
                    if (dialog.showAndGet()) {
                        val selected = dialog.selectedKeys()
                        if (selected.isNotEmpty()) {
                            val deletedEntries = deleteKeys(project, selected)
                            Messages.showInfoMessage(
                                project,
                                "Deleted ${selected.size} key(s) across $deletedEntries locale entrie(s).",
                                "Cleanup Unused Keys"
                            )
                        }
                    }
                }
            }
        })
    }

    // ── Detection (read actions) ──────────────────────────────────────────────

    /**
     * True when any locale's property for [key] — or any of its ancestor
     * properties — has a PSI reference from code. The ancestor walk protects
     * children of dynamically-accessed parents (template keys).
     */
    internal fun hasPsiReferences(sources: List<LocalizationSource>, key: String): Boolean {
        return leafProperties(sources, key).any { property ->
            generateSequence(property) { current ->
                PsiTreeUtil.getParentOfType(current, JsonProperty::class.java, YAMLKeyValue::class.java)
            }.any { ancestor -> ReferencesSearch.search(ancestor).findFirst() != null }
        }
    }

    /**
     * Resolves [key] in every matching localization source and returns the
     * property elements (JsonProperty / YAMLKeyValue) holding its value, one
     * per locale where the key exists.
     *
     * Takes the already-loaded [sources]: resolving them per key turned the scan
     * into O(keys × translation files) full PSI rebuilds.
     */
    internal fun leafProperties(sources: List<LocalizationSource>, key: String): List<PsiElement> {
        val fullKey = KeysSynchronizer().buildFullKey(key)
        val namespace = fullKey.ns?.text
        return sources
            .filter { namespace == null || TranslationDataLoader.extractNamespace(it) == namespace }
            .mapNotNull { source ->
                val ref = resolveCompositeKey(fullKey.compositeKey, source) ?: return@mapNotNull null
                if (ref.unresolved.isNotEmpty() || ref.element == null) return@mapNotNull null
                val value = ref.element.value()
                PsiTreeUtil.getParentOfType(value, JsonProperty::class.java, YAMLKeyValue::class.java)
            }
    }

    // ── Deletion (single write action) ────────────────────────────────────────

    /**
     * Deletes every locale's property for each of [keys], in one
     * WriteCommandAction — a single undo restores the whole cleanup.
     * Pure of any UI (testable); returns the number of deleted locale entries.
     */
    internal fun deleteKeys(project: Project, keys: List<String>): Int {
        val properties = ReadAction.compute<List<PsiElement>, RuntimeException> {
            // One source load for the whole batch, not one per key.
            val sources = project.service<LocalizationSourceService>().findAllSources(project)
            keys.flatMap { leafProperties(sources, it) }
        }
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project, "Cleanup Unused i18n Keys", null, {
                properties.forEach { if (it.isValid) deletePropertyAndSeparator(it) }
            })
        }
        return properties.size
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

    private val model = object : DefaultTableModel(arrayOf("Delete", "Key", "Locales"), 0) {
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, column: Int) = column == 0
    }

    init {
        title = "Cleanup Unused Keys — Preview (${orphans.size} candidates)"
        setOKButtonText("Delete Selected")
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
            JBLabel("Keys reachable through dynamic template usages are already excluded. Deletion removes the key from every locale (single undo)."),
            BorderLayout.SOUTH
        )
        return panel
    }
}
