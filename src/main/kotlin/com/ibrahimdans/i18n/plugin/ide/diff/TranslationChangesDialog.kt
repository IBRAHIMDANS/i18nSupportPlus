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
 */
class TranslationChangesDialog(project: Project, private val found: TranslationChanges) : DialogWrapper(project) {

    private val model = object : DefaultTableModel() {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val laggingOnly = JBCheckBox(PluginBundle.message("diff.translation.lagging.only", found.lagging.size))

    init {
        title = PluginBundle.message("diff.translation.title")
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
            model.setColumnIdentifiers(arrayOf(column("key"), column("locale"), column("change")))
            val behind = PluginBundle.message("diff.translation.kind.lagging", found.referenceLocale ?: "")
            found.lagging.forEach { model.addRow(arrayOf(keyOf(it.namespace, it.path), it.locale, behind)) }
        } else {
            model.setColumnIdentifiers(arrayOf(column("key"), column("locale"), column("change"), column("before"), column("after")))
            found.changes.forEach {
                model.addRow(arrayOf(keyOf(it.namespace, it.path), it.locale, kindOf(it.kind), it.before.orEmpty(), it.after.orEmpty()))
            }
        }
    }

    private fun column(name: String) = PluginBundle.message("diff.translation.column.$name")

    private fun kindOf(kind: TranslationChange.Kind) = PluginBundle.message("diff.translation.kind.${kind.name.lowercase()}")

    private fun keyOf(namespace: String, path: List<String>) = "$namespace:${path.joinToString(".")}"
}
