package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.plugin.ide.actions.MachineFill
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.translate.TranslatedBy
import com.ibrahimdans.i18n.plugin.translate.Translation
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.table.DefaultTableModel

/**
 * The preview of *Fill Missing Translations*: one row per key — keep it or not, key, source text,
 * proposal, note, engine — local or not. A proposal the engine got wrong, or a key not sent, shows its reason in the note,
 * unchecked and locked. A plural form translated from `other` comes unchecked, noted for review.
 * Filling several locales at once adds a *Locale* column after the key.
 *
 * Under the table, *Mark written values for review* when the values land under [reviewRoots] — the
 * roots of the modules they belong to: checked when reviews are already on in all of them (their
 * `.i18n-reviews` folder exists), unchecked otherwise. Checking it turns them on there when writing.
 */
internal class MachineTranslationPreviewDialog(
    project: Project,
    target: String,
    private val proposals: List<MachineFill.Proposal>,
    reviewRoots: Collection<VirtualFile> = emptyList(),
) : DialogWrapper(project) {

    /** Several target locales in one preview: each row says which one it fills. */
    private val severalLocales = proposals.map { it.locale }.distinct().size > 1

    internal val model = object : DefaultTableModel(
        listOfNotNull(
            "",
            PluginBundle.message("action.fill.column.key"),
            PluginBundle.message("action.fill.column.locale").takeIf { severalLocales },
            PluginBundle.message("action.fill.column.source"),
            PluginBundle.message("action.fill.column.proposal"),
            PluginBundle.message("action.fill.column.note"),
            PluginBundle.message("action.fill.column.engine")
        ).toTypedArray(),
        0
    ) {
        override fun getColumnClass(column: Int): Class<*> = if (column == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, column: Int) = column == 0 && proposals[row].result is Translation.Done
    }

    /** Whether the values written are marked to review; hidden when no root is known to keep the marks. */
    internal val markForReview = JBCheckBox(PluginBundle.message("action.fill.preview.markReview")).apply {
        val store = ReviewStore.getInstance(project)
        val enabled = reviewRoots.isNotEmpty() && reviewRoots.all { store.isEnabled(it) }
        isSelected = enabled
        isVisible = reviewRoots.isNotEmpty()
        if (!enabled) toolTipText = PluginBundle.message("action.fill.preview.markReview.enable")
    }

    init {
        proposals.forEach { proposal ->
            val row = when (val result = proposal.result) {
                is Translation.Done ->
                    if (proposal.item.needsReview) Triple(false, result.text, PluginBundle.message("action.fill.review"))
                    else Triple(true, result.text, "")
                is Translation.Failed -> Triple(false, "", PluginBundle.message("action.fill.rejected", result.reason))
            }
            val engine = (proposal.result as? Translation.Done)?.engine?.let { engineLabel(it) }.orEmpty()
            model.addRow(listOfNotNull<Any>(
                row.first, proposal.item.key, proposal.locale.takeIf { severalLocales }, proposal.item.source, row.second, row.third, engine
            ).toTypedArray())
        }
        title = PluginBundle.message("action.fill.preview.title", target, proposals.size)
        setOKButtonText(PluginBundle.message("action.fill.preview.ok"))
        init()
    }

    /** The checked proposals, `key -> translation`, of a single target locale. */
    fun accepted(): Map<String, String> = acceptedByLocale().values.fold(emptyMap()) { all, keys -> all + keys }

    /** The checked proposals, `locale -> key -> translation`. */
    fun acceptedByLocale(): Map<String, Map<String, String>> =
        proposals.indices
            .filter { model.getValueAt(it, 0) == true }
            .mapNotNull { row -> (proposals[row].result as? Translation.Done)?.let { proposals[row] to it.text } }
            .groupBy({ it.first.locale }, { it.first.item.key to it.second })
            .mapValues { it.value.toMap() }

    /**
     * Where each checked proposal comes from, `locale -> key -> machine:<engine>`, when the values
     * written are to be marked for review; null when they are not.
     */
    fun reviewOrigins(): Map<String, Map<String, String>>? {
        if (!markForReview.isVisible || !markForReview.isSelected) return null
        return proposals.indices
            .filter { model.getValueAt(it, 0) == true }
            .mapNotNull { row -> (proposals[row].result as? Translation.Done)?.let { proposals[row] to MachineFill.originOf(it) } }
            .groupBy({ it.first.locale }, { it.first.item.key to it.second })
            .mapValues { it.value.toMap() }
    }

    override fun createCenterPanel(): JComponent {
        val table = JBTable(model)
        table.setShowGrid(false)
        table.columnModel.getColumn(0).maxWidth = 32
        val rejected = proposals.count { it.result is Translation.Failed }
        val review = proposals.count { it.item.needsReview && it.result is Translation.Done }
        return JPanel(BorderLayout(0, 6)).apply {
            add(JScrollPane(table).apply { preferredSize = Dimension(820, 360) }, BorderLayout.CENTER)
            val notes = listOfNotNull(
                PluginBundle.message("action.fill.preview.rejected", rejected).takeIf { rejected > 0 },
                PluginBundle.message("action.fill.preview.review", review).takeIf { review > 0 }
            )
            val south = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
            if (notes.isNotEmpty()) south.add(JBLabel(notes.joinToString("<br>", "<html>", "</html>")))
            south.add(markForReview)
            add(south, BorderLayout.SOUTH)
        }
    }

    internal companion object {
        /** `DeepL`, or `Ollama (local)` for an engine on the user's machine: after a fallback, where each text went. */
        fun engineLabel(engine: TranslatedBy): String =
            if (engine.local) PluginBundle.message("action.fill.engine.local", engine.name) else engine.name
    }
}
