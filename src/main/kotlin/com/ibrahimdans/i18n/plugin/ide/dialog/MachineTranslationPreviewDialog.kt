package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.plugin.ide.actions.MachineFill
import com.ibrahimdans.i18n.plugin.translate.Translation
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.table.DefaultTableModel

/**
 * The preview of *Fill Missing Translations*: one row per key — keep it or not, key, source text,
 * proposal. A proposal the engine got wrong shows its reason instead, unchecked and locked.
 */
internal class MachineTranslationPreviewDialog(
    project: Project,
    target: String,
    private val proposals: List<MachineFill.Proposal>,
) : DialogWrapper(project) {

    internal val model = object : DefaultTableModel(
        arrayOf(
            "",
            PluginBundle.message("action.fill.column.key"),
            PluginBundle.message("action.fill.column.source"),
            PluginBundle.message("action.fill.column.proposal")
        ),
        0
    ) {
        override fun getColumnClass(column: Int): Class<*> = if (column == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, column: Int) = column == 0 && proposals[row].result is Translation.Done
    }

    init {
        proposals.forEach { proposal ->
            val (accepted, text) = when (val result = proposal.result) {
                is Translation.Done -> true to result.text
                is Translation.Failed -> false to PluginBundle.message("action.fill.rejected", result.reason)
            }
            model.addRow(arrayOf<Any>(accepted, proposal.item.key, proposal.item.source, text))
        }
        title = PluginBundle.message("action.fill.preview.title", target, proposals.size)
        setOKButtonText(PluginBundle.message("action.fill.preview.ok"))
        init()
    }

    /** The checked proposals, `key -> translation`. */
    fun accepted(): Map<String, String> =
        proposals.indices
            .filter { model.getValueAt(it, 0) == true }
            .mapNotNull { row -> (proposals[row].result as? Translation.Done)?.let { proposals[row].item.key to it.text } }
            .toMap()

    override fun createCenterPanel(): JComponent {
        val table = JBTable(model)
        table.setShowGrid(false)
        table.columnModel.getColumn(0).maxWidth = 32
        val rejected = proposals.count { it.result is Translation.Failed }
        return JPanel(BorderLayout(0, 6)).apply {
            add(JScrollPane(table).apply { preferredSize = Dimension(820, 360) }, BorderLayout.CENTER)
            if (rejected > 0) add(JBLabel(PluginBundle.message("action.fill.preview.rejected", rejected)), BorderLayout.SOUTH)
        }
    }
}
