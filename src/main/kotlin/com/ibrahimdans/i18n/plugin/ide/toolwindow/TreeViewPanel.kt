package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

/**
 * Panel displaying translations as a hierarchical tree.
 * Nodes are color-coded: red for missing keys, orange for empty values.
 *
 * When [moduleConfig] is non-null, only translations from that module are shown.
 */
class TreeViewPanel(private val project: Project, private val moduleConfig: ModuleConfig? = null) : JPanel(BorderLayout()) {

    private val viewModel = TreeViewModel()
    private val rootTreeNode = DefaultMutableTreeNode("Translations")
    private val treeModel = DefaultTreeModel(rootTreeNode)
    private val tree = Tree(treeModel)
    private var allLocales: List<String> = emptyList()
    private var missingKeys: Set<String> = emptySet()
    private var emptyValueKeys: Set<String> = emptySet()
    private var lastTranslationRoot: TranslationNode = TranslationNode(key = "root", fullPath = "", values = emptyMap())
    private var currentFilter: String = ""

    init {
        tree.cellRenderer = TranslationTreeCellRenderer()
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val data = selectedNode.userObject as? TranslationNodeData ?: return
                    if (data.isLeaf) {
                        val keyString = data.fullPath
                        val colonIdx = keyString.indexOf(':')
                        val (ns, keyPath) = if (colonIdx > 0) {
                            Literal(keyString.substring(0, colonIdx)) to keyString.substring(colonIdx + 1)
                        } else {
                            null to keyString
                        }
                        val fullKey = FullKey(source = keyString, ns = ns, compositeKey = keyPath.split('.').map { Literal(it) })
                        val dialog = TranslationDialog(project, fullKey, Mode.EDIT)
                        if (dialog.showAndGet()) {
                            refresh()
                        }
                    }
                }
            }
        })
        add(JScrollPane(tree), BorderLayout.CENTER)
    }

    /**
     * Reloads translation data and rebuilds the tree.
     */
    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val translationRoot = viewModel.loadTranslations(project, moduleConfig)
            allLocales = TranslationDataLoader.discoverLocales(project, moduleConfig)
            missingKeys = viewModel.getMissingKeys(translationRoot, allLocales)
            emptyValueKeys = collectEmptyValueKeys(translationRoot)
            lastTranslationRoot = translationRoot

            ApplicationManager.getApplication().invokeLater {
                rebuildTree(viewModel.filter(currentFilter, translationRoot))
            }
        }
    }

    /**
     * Applies a text filter to the tree without reloading translation data.
     * Pass an empty string to clear the filter.
     */
    fun applyFilter(query: String) {
        currentFilter = query
        rebuildTree(viewModel.filter(query, lastTranslationRoot))
    }

    private fun rebuildTree(root: TranslationNode) {
        rootTreeNode.removeAllChildren()
        buildTreeNodes(root, rootTreeNode)
        treeModel.reload()
        tree.expandRow(0)
    }

    private fun buildTreeNodes(node: TranslationNode, parent: DefaultMutableTreeNode) {
        for ((_, child) in node.children.toSortedMap()) {
            val data = TranslationNodeData(
                key = child.key,
                fullPath = child.fullPath,
                isLeaf = child.isLeaf,
                isMissing = child.fullPath in missingKeys,
                hasEmptyValue = child.fullPath in emptyValueKeys
            )
            val treeNode = DefaultMutableTreeNode(data)
            parent.add(treeNode)
            if (child.children.isNotEmpty()) {
                buildTreeNodes(child, treeNode)
            }
        }
    }

    private fun collectEmptyValueKeys(node: TranslationNode): Set<String> {
        val result = mutableSetOf<String>()
        collectEmptyValues(node, result)
        return result
    }

    private fun collectEmptyValues(node: TranslationNode, result: MutableSet<String>) {
        if (node.isLeaf && node.values.any { it.value.isBlank() }) {
            result.add(node.fullPath)
        }
        for (child in node.children.values) {
            collectEmptyValues(child, result)
        }
    }

    /**
     * Data holder for tree node user objects.
     */
    private data class TranslationNodeData(
        val key: String,
        val fullPath: String,
        val isLeaf: Boolean,
        val isMissing: Boolean,
        val hasEmptyValue: Boolean
    ) {
        override fun toString(): String = key
    }

    /**
     * Custom cell renderer with color coding for translation status.
     */
    private inner class TranslationTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any?,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): Component {
            val component = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val node = value as? DefaultMutableTreeNode ?: return component
            val data = node.userObject as? TranslationNodeData ?: return component

            foreground = when {
                data.isMissing -> JBColor.RED
                data.hasEmptyValue -> JBColor.ORANGE
                else -> if (sel) getTextSelectionColor() else getTextNonSelectionColor()
            }

            return component
        }
    }
}
