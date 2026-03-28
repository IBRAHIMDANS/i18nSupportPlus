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
