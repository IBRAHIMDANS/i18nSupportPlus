package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.openapi.project.Project

/**
 * Represents a node in the hierarchical translation tree.
 */
data class TranslationNode(
    val key: String,
    val fullPath: String,
    var values: Map<String, String>,
    val children: MutableMap<String, TranslationNode> = mutableMapOf(),
    var isLeaf: Boolean = false
)

/**
 * State of one locale for a single translation key.
 *  - [PRESENT] the locale carries a non-blank value
 *  - [EMPTY]   the locale carries the key but its value is blank
 *  - [MISSING] the locale does not carry the key at all
 */
enum class LocaleState { PRESENT, EMPTY, MISSING }

/**
 * Aggregate status of a tree node — the worst [LocaleState] found on the node
 * itself (when it is a leaf) and on every descendant leaf.
 *
 * This is the value the renderer turns into an icon *and* a text label: the status
 * of a key must never be carried by a color alone.
 */
enum class KeyStatus { COMPLETE, EMPTY, MISSING }

/**
 * How many of the leaf keys under a node are fully translated.
 * Drives the `12/14 (86%)` badge shown on branch nodes, so that a group holding a
 * gap can be spotted without expanding it.
 */
data class NodeCompleteness(val complete: Int, val total: Int) {
    val percent: Int get() = if (total <= 0) 100 else complete * 100 / total
    val isComplete: Boolean get() = complete >= total
}

/**
 * Everything the tree cell renderer needs to know about one node, computed headless
 * so it can be unit-tested without a Swing component.
 *
 * [localeStates] is empty for a branch node: per-locale badges only make sense on a
 * key. It keeps the order of the locale list it was computed from.
 */
data class NodeStatus(
    val status: KeyStatus,
    val localeStates: Map<String, LocaleState>,
    val completeness: NodeCompleteness,
)

/**
 * View model for the tree-based translation view.
 * Builds a hierarchical tree from flat translation data.
 */
class TreeViewModel {

    /**
     * Loads translations and builds the hierarchical tree structure.
     * When [moduleConfig] is non-null, only translations from that module are loaded.
     */
    fun loadTranslations(project: Project, moduleConfig: ModuleConfig? = null): TranslationNode {
        val flatData = TranslationDataLoader.loadAllTranslations(project, moduleConfig)
        val root = TranslationNode(key = "root", fullPath = "", values = emptyMap())

        for ((fullKey, localeValues) in flatData) {
            val parts = fullKey.split(".")
            var current = root
            for ((index, part) in parts.withIndex()) {
                val partialPath = parts.take(index + 1).joinToString(".")
                val isLast = index == parts.lastIndex
                current = current.children.getOrPut(part) {
                    TranslationNode(
                        key = part,
                        fullPath = partialPath,
                        values = if (isLast) localeValues else emptyMap(),
                        isLeaf = isLast
                    )
                }
                // Update a branch node that is also a leaf (key exists at multiple depths)
                if (isLast && current.values.isEmpty() && localeValues.isNotEmpty()) {
                    current.values = localeValues
                    current.isLeaf = true
                }
            }
        }

        return root
    }

    /**
     * Finds keys that are missing in at least one locale.
     */
    fun getMissingKeys(root: TranslationNode, allLocales: List<String>): Set<String> {
        val missing = mutableSetOf<String>()
        collectMissingKeys(root, allLocales, missing)
        return missing
    }

    private fun collectMissingKeys(
        node: TranslationNode,
        allLocales: List<String>,
        result: MutableSet<String>
    ) {
        if (node.isLeaf && allLocales.any { it !in node.values }) {
            result.add(node.fullPath)
        }
        for (child in node.children.values) {
            collectMissingKeys(child, allLocales, result)
        }
    }

    /**
     * Describes every node of [root] in a single bottom-up traversal, keyed by
     * [TranslationNode.fullPath] (the root itself is described under `""`).
     *
     * One pass rather than one call per node: the renderer asks for a node's status on
     * every repaint, and recomputing a branch's completeness from its subtree each time
     * would be quadratic on a large translation set.
     */
    fun describeTree(root: TranslationNode, allLocales: List<String>): Map<String, NodeStatus> {
        val result = mutableMapOf<String, NodeStatus>()
        describeNode(root, allLocales, result)
        return result
    }

    /**
     * Per-locale state of a leaf key, in the order of [allLocales].
     * Locales the key carries but that were not discovered in the project are appended,
     * so a stale value is never silently hidden. Returns an empty map for a branch node.
     */
    fun localeStates(node: TranslationNode, allLocales: List<String>): Map<String, LocaleState> {
        if (!node.isLeaf) return emptyMap()
        val locales = (allLocales + node.values.keys.sorted()).distinct()
        return locales.associateWith { locale ->
            val value = node.values[locale]
            when {
                value == null -> LocaleState.MISSING
                value.isBlank() -> LocaleState.EMPTY
                else -> LocaleState.PRESENT
            }
        }
    }

    private fun describeNode(
        node: TranslationNode,
        allLocales: List<String>,
        out: MutableMap<String, NodeStatus>,
    ): NodeStatus {
        // Children first: a branch's own status and completeness are aggregates of theirs.
        val childStatuses = node.children.values.map { describeNode(it, allLocales, out) }

        val localeStates = localeStates(node, allLocales)
        // A node can be both a leaf and a branch (a key that also has sub-keys):
        // its own status counts alongside its children's.
        val ownStatus = if (node.isLeaf) worstStatus(localeStates.values) else null

        val status = worstOf(listOfNotNull(ownStatus) + childStatuses.map { it.status })
        val completeness = childStatuses.fold(ownCompleteness(node.isLeaf, ownStatus)) { acc, child ->
            NodeCompleteness(
                complete = acc.complete + child.completeness.complete,
                total = acc.total + child.completeness.total,
            )
        }

        val nodeStatus = NodeStatus(status, localeStates, completeness)
        out[node.fullPath] = nodeStatus
        return nodeStatus
    }

    private fun ownCompleteness(isLeaf: Boolean, ownStatus: KeyStatus?): NodeCompleteness =
        if (!isLeaf) NodeCompleteness(0, 0)
        else NodeCompleteness(complete = if (ownStatus == KeyStatus.COMPLETE) 1 else 0, total = 1)

    private fun worstStatus(states: Collection<LocaleState>): KeyStatus = when {
        states.any { it == LocaleState.MISSING } -> KeyStatus.MISSING
        states.any { it == LocaleState.EMPTY } -> KeyStatus.EMPTY
        else -> KeyStatus.COMPLETE
    }

    private fun worstOf(statuses: Collection<KeyStatus>): KeyStatus = when {
        statuses.any { it == KeyStatus.MISSING } -> KeyStatus.MISSING
        statuses.any { it == KeyStatus.EMPTY } -> KeyStatus.EMPTY
        else -> KeyStatus.COMPLETE
    }

    /**
     * Returns a filtered copy of the tree keeping only nodes whose key or
     * any translation value contains [query] (case-insensitive).
     * Branch nodes are kept if at least one descendant matches.
     */
    fun filter(query: String, root: TranslationNode): TranslationNode {
        if (query.isBlank()) return root
        val filteredRoot = root.copy(children = mutableMapOf())
        for ((key, child) in root.children) {
            val filtered = filterNode(query.lowercase(), child)
            if (filtered != null) {
                filteredRoot.children[key] = filtered
            }
        }
        return filteredRoot
    }

    private fun filterNode(lowerQuery: String, node: TranslationNode): TranslationNode? {
        // Check if this node itself matches (key or any value)
        val keyMatches = node.fullPath.lowercase().contains(lowerQuery)
        val valueMatches = node.values.values.any { it.lowercase().contains(lowerQuery) }

        // Recursively filter children
        val filteredChildren = mutableMapOf<String, TranslationNode>()
        for ((key, child) in node.children) {
            val filteredChild = filterNode(lowerQuery, child)
            if (filteredChild != null) {
                filteredChildren[key] = filteredChild
            }
        }

        return when {
            keyMatches || valueMatches -> node.copy(children = filteredChildren)
            filteredChildren.isNotEmpty() -> node.copy(children = filteredChildren)
            else -> null
        }
    }
}
