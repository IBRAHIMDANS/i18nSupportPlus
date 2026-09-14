package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.openapi.project.Project

/**
 * Represents a node in the hierarchical translation tree.
 *
 * [namespace] is set on a namespace group node only — the level [TreeViewModel.buildTree]
 * inserts under the root when the data spans several namespaces — and null on every key
 * node. Its [fullPath] is then the namespace prefix alone (`common:`), which no key can spell
 * since a key always carries a path after the separator, so statuses keyed by path never
 * collide with a key's.
 */
data class TranslationNode(
    val key: String,
    val fullPath: String,
    var values: Map<String, String>,
    val children: MutableMap<String, TranslationNode> = mutableMapOf(),
    var isLeaf: Boolean = false,
    val namespace: NamespaceFilter? = null,
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
        return buildTree(flatData, Settings.getInstance(project).config())
    }

    /**
     * The tree of [flatData], split into levels the way [KeySpelling] joined them: on the configured
     * key separator, and not at all for a flat key. Each key node's `fullPath` is the whole key,
     * namespace prefix included, so [KeySpelling] can take it apart again.
     *
     * When at least one key carries a namespace, the first level under the root is the namespace
     * itself — one group per namespace, plus a [NamespaceFilter.Default] group for the keys that
     * carry none — and the keys hang below it without their prefix. The prefix used to be glued
     * to the first segment instead (`common:actions`, `common:appName`, …), which spread one
     * namespace over as many top-level rows as it had first segments: nothing marked where
     * `common` ended and `dashboard` began, no row said how complete a namespace was, and none
     * could fold it away. A project with a single, default namespace has nothing to group and
     * keeps its keys directly under the root.
     */
    internal fun buildTree(flatData: Map<String, Map<String, String>>, config: Config): TranslationNode {
        val root = TranslationNode(key = "root", fullPath = "", values = emptyMap())
        val grouped = flatData.keys.any { KeySpelling.namespaceOf(it) != null }

        for ((fullKey, localeValues) in flatData) {
            val namespace = KeySpelling.namespaceOf(fullKey)
            val segments = KeySpelling.segmentsOf(fullKey, config)
            val prefix = namespace?.let { it + KeySpelling.NAMESPACE_SEPARATOR }.orEmpty()
            var current = if (grouped) namespaceGroup(root, namespace, config) else root
            for ((index, part) in segments.withIndex()) {
                val partialPath = prefix + segments.take(index + 1).fold("") { path, segment -> KeySpelling.child(config, path, segment) }
                val isLast = index == segments.lastIndex
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
     * The group node of [namespace] under [root], created on first use. The default group is
     * keyed by its label rather than by a name: it stands for the keys spelled without a
     * prefix, which is what [NamespaceFilter.Default] means in the table's combo too.
     */
    private fun namespaceGroup(root: TranslationNode, namespace: String?, config: Config): TranslationNode {
        val filter = if (namespace == null) NamespaceFilter.Default else NamespaceFilter.Named(namespace)
        val prefix = namespace?.let { it + KeySpelling.NAMESPACE_SEPARATOR } ?: KeySpelling.NAMESPACE_SEPARATOR
        val key = namespace ?: filter.label(config)
        return root.children.getOrPut(key) {
            TranslationNode(key = key, fullPath = prefix, values = emptyMap(), namespace = filter)
        }
    }

    /**
     * The children of [node] in display order: the default namespace's group first — it is
     * the project's main namespace, and the Stats and the table's combo already lead with it
     * — then the rest by key. The tree used to sort every level by key alone, which put
     * `common (default)` between `auth` and `dashboard` while the other views led with it.
     */
    fun orderedChildren(node: TranslationNode): List<TranslationNode> =
        node.children.values.sortedWith(compareBy({ it.namespace !is NamespaceFilter.Default }, { it.key }))

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
