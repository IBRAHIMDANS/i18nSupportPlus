package com.ibrahimdans.i18n.plugin.tree

/**
 * Tree wrapper
 */
interface Tree<T> {
    /**
     * Finds child by name
     */
    fun findChild(name: String): Tree<T>?

    /**
     * Checks if current tree node is a leaf
     */
    fun isLeaf(): Boolean = !isTree()

    /**
     * Checks if current tree node is a tree
     */
    fun isTree(): Boolean

    /**
     * Gets current node underlying value
     */
    fun value(): T

    /**
     * Finds children by name starting with prefix
     */
    fun findChildren(prefix: String): List<Tree<T>>

    /**
     * Every child of this node as (name, node), the node being the one [findChild] returns for
     * that name — in a single walk, where asking [findChild] for each name in turn rescans the
     * children every time and turns listing a level into a quadratic walk.
     *
     * Null when the format offers no such walk: [T] is generic, so names cannot be read here, and
     * the caller falls back to [findChild].
     */
    fun entries(): List<Pair<String, Tree<T>>>? = null
}