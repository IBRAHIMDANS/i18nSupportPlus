package com.ibrahimdans.i18n.plugin.ide.diff

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

/**
 * The translation changes as a table — key, locale, change, before, after — with a switch to the
 * locales left behind the reference, the follow-up a review usually asks for.
 *
 * [dialogTitle] says what the changes are compared with: the working copy by default, a branch
 * when they come from one.
 */
class TranslationChangesDialog(
    project: Project,
    private val found: TranslationChanges,
    dialogTitle: String = PluginBundle.message("diff.translation.title"),
) : DialogWrapper(project) {

    private val model = object : DefaultTableModel() {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val laggingOnly = JBCheckBox(PluginBundle.message("diff.translation.lagging.only", found.lagging.size))

    init {
        title = dialogTitle
        laggingOnly.isEnabled = found.lagging.isNotEmpty()
        laggingOnly.addActionListener { fill() }
        fill()
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(laggingOnly, BorderLayout.NORTH)
        add(JBScrollPane(JBTable(model)).apply { preferredSize = Dimension(820, 420) }, BorderLayout.CENTER)
    }

    private fun fill() {
        model.setRowCount(0)
        if (laggingOnly.isSelected) {
            model.setColumnIdentifiers(arrayOf(keyColumn(), localeColumn(), changeColumn()))
            val behind = PluginBundle.message("diff.translation.kind.lagging", found.referenceLocale ?: "")
            found.lagging.forEach { model.addRow(arrayOf(keyOf(it.namespace, it.path), it.locale, behind)) }
        } else {
            model.setColumnIdentifiers(
                arrayOf(
                    keyColumn(), localeColumn(), changeColumn(),
                    PluginBundle.message("diff.translation.column.before"),
                    PluginBundle.message("diff.translation.column.after")
                )
            )
            found.changes.forEach {
                model.addRow(arrayOf(keyOf(it.namespace, it.path), it.locale, kindOf(it.kind), it.before.orEmpty(), it.after.orEmpty()))
            }
        }
    }

    // Keys stay literal so PluginBundleTest and the IDE can check them against the bundle.
    private fun keyColumn() = PluginBundle.message("diff.translation.column.key")

    private fun localeColumn() = PluginBundle.message("diff.translation.column.locale")

    private fun changeColumn() = PluginBundle.message("diff.translation.column.change")

    private fun kindOf(kind: TranslationChange.Kind) = when (kind) {
        TranslationChange.Kind.ADDED -> PluginBundle.message("diff.translation.kind.added")
        TranslationChange.Kind.REMOVED -> PluginBundle.message("diff.translation.kind.removed")
        TranslationChange.Kind.MODIFIED -> PluginBundle.message("diff.translation.kind.modified")
    }

    private fun keyOf(namespace: String, path: List<String>) = "$namespace:${path.joinToString(".")}"
}
