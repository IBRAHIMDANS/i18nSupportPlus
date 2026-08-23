package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.tree.Tree as KeyTree
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

// ── Status marks ──────────────────────────────────────────────────────────────
// The status of a key must survive a screenshot printed in black and white, and a
// reader who does not separate red from orange (~8% of men). Every color below is
// therefore doubled by a shape: an icon on the node, one of these marks on the badge.
private const val MARK_COMPLETE = "✓"
private const val MARK_EMPTY = "!"
private const val MARK_MISSING = "✗"

// ── Status colors ─────────────────────────────────────────────────────────────
// namedColor, never JBColor.RED / JBColor.ORANGE: those are fixed constants that no
// theme and no colorblind-friendly palette can reach. A named key resolves against the
// running theme first and only falls back to the literal pair given here.
private val COLOR_COMPLETE = JBColor.namedColor("Label.successForeground", JBColor(Color(0x1E8A3B), Color(0x5FAD65)))
private val COLOR_EMPTY = JBColor.namedColor("Label.warningForeground", JBColor(Color(0xA1740B), Color(0xF2C55C)))
private val COLOR_MISSING = JBColor.namedColor("Label.errorForeground", JBColor(Color(0xC7222D), Color(0xFF5261)))

private val ICON_COMPLETE: Icon = IconLoader.getIcon("/icons/gutter_resolved.svg", TreeViewPanel::class.java)
private val ICON_EMPTY: Icon = IconLoader.getIcon("/icons/gutter_partial.svg", TreeViewPanel::class.java)
private val ICON_MISSING: Icon = IconLoader.getIcon("/icons/gutter_missing.svg", TreeViewPanel::class.java)

private const val ACTION_EDIT = "i18n.tree.edit"
private const val ACTION_OPEN_FILE = "i18n.tree.openFile"

private val NEUTRAL_STATUS = NodeStatus(KeyStatus.COMPLETE, emptyMap(), NodeCompleteness(0, 0))

/**
 * Panel displaying translations as a hierarchical tree.
 *
 * Each key carries its status three times over — a status icon on the left, a per-locale
 * badge (`EN✓ FR✗`) on the right, and a color — so that neither a color-blind reader nor a
 * custom theme can erase it. Branch nodes show how many of the keys they hold are fully
 * translated (`12/14 (86%)`), which is what lets a gap be found without expanding
 * everything. A permanent legend sits at the bottom of the panel.
 *
 * Interaction: double-click or Enter edits the key, F4 opens the translation file at the
 * key, type-to-search jumps to a key, and a right-click menu carries the same actions.
 *
 * When [moduleConfig] is non-null, only translations from that module are shown.
 */
class TreeViewPanel(private val project: Project, private val moduleConfig: ModuleConfig? = null) : JPanel(BorderLayout()) {

    private val viewModel = TreeViewModel()
    private val rootTreeNode = DefaultMutableTreeNode(PluginBundle.message("toolwindow.tree.root"))
    private val treeModel = DefaultTreeModel(rootTreeNode)
    private val tree = Tree(treeModel)
    private var allLocales: List<String> = emptyList()

    /**
     * Status of every node, keyed by full path — computed once per reload by
     * [TreeViewModel.describeTree] rather than per repaint by the renderer.
     */
    private var nodeStatuses: Map<String, NodeStatus> = emptyMap()
    private var lastTranslationRoot: TranslationNode = TranslationNode(key = "root", fullPath = "", values = emptyMap())
    private var currentFilter: String = ""

    init {
        tree.cellRenderer = TranslationTreeCellRenderer()
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
                    selectedNodeData()?.let { editTranslation(it) }
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
        })
        registerShortcuts()
        // Searching inside collapsed nodes: the interesting keys are the ones still folded away.
        TreeSpeedSearch.installOn(tree, true) { path ->
            val node = path.lastPathComponent as? DefaultMutableTreeNode
            (node?.userObject as? TranslationNodeData)?.fullPath ?: node?.userObject?.toString() ?: ""
        }

        add(JScrollPane(tree), BorderLayout.CENTER)
        add(buildLegend(), BorderLayout.SOUTH)
    }

    /**
     * Reloads translation data and rebuilds the tree.
     */
    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val translationRoot = viewModel.loadTranslations(project, moduleConfig)
            allLocales = TranslationDataLoader.discoverLocales(project, moduleConfig)
            nodeStatuses = viewModel.describeTree(translationRoot, allLocales)
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
            // Statuses are looked up by full path, so a filtered view still reports the
            // completeness of the real key set rather than of what survived the filter.
            val data = TranslationNodeData(
                key = child.key,
                fullPath = child.fullPath,
                isLeaf = child.isLeaf,
                status = nodeStatuses[child.fullPath] ?: NEUTRAL_STATUS
            )
            val treeNode = DefaultMutableTreeNode(data)
            parent.add(treeNode)
            if (child.children.isNotEmpty()) {
                buildTreeNodes(child, treeNode)
            }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun registerShortcuts() {
        val inputMap = tree.getInputMap(JComponent.WHEN_FOCUSED)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTION_EDIT)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), ACTION_OPEN_FILE)
        tree.actionMap.put(ACTION_EDIT, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                selectedNodeData()?.let { editTranslation(it) }
            }
        })
        tree.actionMap.put(ACTION_OPEN_FILE, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                selectedNodeData()?.let { openTranslationFile(it) }
            }
        })
    }

    private fun selectedNodeData(): TranslationNodeData? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? TranslationNodeData

    private fun showContextMenu(e: MouseEvent) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        tree.selectionPath = path
        val data = selectedNodeData() ?: return

        val menu = JPopupMenu()
        menu.add(JMenuItem(PluginBundle.message("toolwindow.tree.menu.edit")).apply {
            isEnabled = data.isLeaf
            addActionListener { editTranslation(data) }
        })
        menu.add(JMenuItem(PluginBundle.message("toolwindow.tree.menu.open.file")).apply {
            addActionListener { openTranslationFile(data) }
        })
        menu.add(JMenuItem(PluginBundle.message("toolwindow.tree.menu.copy.key")).apply {
            addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(data.fullPath)) }
        })
        menu.show(e.component, e.x, e.y)
    }

    private fun editTranslation(data: TranslationNodeData) {
        if (!data.isLeaf) return
        val dialog = TranslationDialog(project, buildFullKey(data.fullPath), Mode.EDIT)
        if (dialog.showAndGet()) {
            refresh()
        }
    }

    /**
     * Opens the first translation file holding [data]'s key, at the key's own offset.
     * Namespaced keys are looked up in the sources of that namespace first, so `common:x`
     * does not land in another file that happens to define `x` too.
     */
    private fun openTranslationFile(data: TranslationNodeData) {
        val keyString = data.fullPath
        ApplicationManager.getApplication().executeOnPooledThread {
            val sources = TranslationDataLoader.findSources(project, moduleConfig)
            val (namespace, segments) = parseTranslationKey(keyString)
            val candidates = if (namespace.isNullOrEmpty()) sources
            else sources.filter { TranslationDataLoader.extractNamespace(it) == namespace }.ifEmpty { sources }

            val target = ReadAction.compute<Pair<VirtualFile, Int>?, RuntimeException> {
                for (source in candidates) {
                    var node: KeyTree<PsiElement>? = source.tree
                    for (segment in segments) {
                        node = node?.findChild(segment)
                    }
                    val psi: PsiElement = node?.value() ?: continue
                    val file = psi.containingFile?.virtualFile ?: continue
                    return@compute file to psi.textOffset
                }
                null
            } ?: return@executeOnPooledThread

            ApplicationManager.getApplication().invokeLater {
                OpenFileDescriptor(project, target.first, target.second).navigate(true)
            }
        }
    }

    /**
     * Builds a FullKey from a flat key string, handling the optional namespace prefix.
     *   "menu.home"        → FullKey(ns=null, compositeKey=[menu, home])
     *   "common:menu.home" → FullKey(ns=Literal(common), compositeKey=[menu, home])
     */
    private fun buildFullKey(keyString: String): FullKey {
        val (namespace, segments) = parseTranslationKey(keyString)
        return FullKey(
            source = keyString,
            ns = namespace?.takeIf { it.isNotEmpty() }?.let { Literal(it) },
            compositeKey = segments.map { Literal(it) }
        )
    }

    // ── Legend ────────────────────────────────────────────────────────────────

    /**
     * Permanent legend: without it the icons and badges are one more code to guess.
     */
    private fun buildLegend(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), JBUI.scale(2)))
        panel.border = JBUI.Borders.empty(2, 4)
        panel.add(caption(PluginBundle.message("toolwindow.tree.legend.title")))
        panel.add(legendItem(ICON_COMPLETE, PluginBundle.message("toolwindow.tree.legend.complete", MARK_COMPLETE), COLOR_COMPLETE))
        panel.add(legendItem(ICON_EMPTY, PluginBundle.message("toolwindow.tree.legend.empty", MARK_EMPTY), COLOR_EMPTY))
        panel.add(legendItem(ICON_MISSING, PluginBundle.message("toolwindow.tree.legend.missing", MARK_MISSING), COLOR_MISSING))
        panel.add(caption(PluginBundle.message("toolwindow.tree.legend.completeness")))
        return panel
    }

    private fun legendItem(icon: Icon, text: String, color: Color): JBLabel =
        JBLabel(text, icon, SwingConstants.LEADING).apply {
            font = JBFont.small()
            foreground = color
        }

    private fun caption(text: String): JBLabel = JBLabel(text).apply {
        font = JBFont.small()
        foreground = SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
    }

    // ── Node model ────────────────────────────────────────────────────────────

    /**
     * Data holder for tree node user objects. [status] is the headless description
     * computed by [TreeViewModel]; the renderer only turns it into pixels.
     */
    private data class TranslationNodeData(
        val key: String,
        val fullPath: String,
        val isLeaf: Boolean,
        val status: NodeStatus
    ) {
        override fun toString(): String = key
    }

    // ── Renderer ──────────────────────────────────────────────────────────────

    /**
     * Draws a node as: status icon + key + per-locale badges (leaf), or
     * folder icon + key + completeness (branch).
     *
     * On a selected row the badge colors are dropped in favour of the selection
     * foreground — the marks and the icon still carry the status, and readable text
     * beats a color fighting the selection band.
     */
    private inner class TranslationTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            val data = node.userObject as? TranslationNodeData
            if (data == null) {
                // The root node holds a plain label, not a translation key.
                append(node.userObject?.toString().orEmpty(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                return
            }

            icon = if (data.isLeaf) statusIcon(data.status.status) else AllIcons.Nodes.Folder
            append(data.key, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (data.isLeaf) appendLocaleBadges(data, selected) else appendCompleteness(data, selected)

            toolTipText = data.fullPath
            // Screen readers get the status as words, not as a color.
            setAccessibleStatusText(statusLabel(data.status.status))
        }

        private fun appendLocaleBadges(data: TranslationNodeData, selected: Boolean) {
            for ((locale, state) in data.status.localeStates) {
                append(" ")
                append("${locale.uppercase()}${mark(state)}", badgeAttributes(state, selected))
            }
        }

        private fun appendCompleteness(data: TranslationNodeData, selected: Boolean) {
            val completeness = data.status.completeness
            if (completeness.total == 0) return
            append("  ")
            append(
                PluginBundle.message(
                    "toolwindow.tree.completeness",
                    completeness.complete,
                    completeness.total,
                    completeness.percent
                ),
                if (selected || completeness.isComplete) SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
                else SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, statusColor(data.status.status))
            )
        }

        private fun badgeAttributes(state: LocaleState, selected: Boolean): SimpleTextAttributes =
            if (selected) SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
            else SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, stateColor(state))
    }

    // ── Status ↔ presentation ─────────────────────────────────────────────────

    private fun statusIcon(status: KeyStatus): Icon = when (status) {
        KeyStatus.COMPLETE -> ICON_COMPLETE
        KeyStatus.EMPTY -> ICON_EMPTY
        KeyStatus.MISSING -> ICON_MISSING
    }

    private fun statusColor(status: KeyStatus): Color = when (status) {
        KeyStatus.COMPLETE -> COLOR_COMPLETE
        KeyStatus.EMPTY -> COLOR_EMPTY
        KeyStatus.MISSING -> COLOR_MISSING
    }

    private fun statusLabel(status: KeyStatus): String = when (status) {
        KeyStatus.COMPLETE -> PluginBundle.message("toolwindow.tree.status.complete")
        KeyStatus.EMPTY -> PluginBundle.message("toolwindow.tree.status.empty")
        KeyStatus.MISSING -> PluginBundle.message("toolwindow.tree.status.missing")
    }

    private fun stateColor(state: LocaleState): Color = when (state) {
        LocaleState.PRESENT -> COLOR_COMPLETE
        LocaleState.EMPTY -> COLOR_EMPTY
        LocaleState.MISSING -> COLOR_MISSING
    }

    private fun mark(state: LocaleState): String = when (state) {
        LocaleState.PRESENT -> MARK_COMPLETE
        LocaleState.EMPTY -> MARK_EMPTY
        LocaleState.MISSING -> MARK_MISSING
    }
}
