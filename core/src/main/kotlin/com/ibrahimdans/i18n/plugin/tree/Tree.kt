package com.ibrahimdans.i18n.plugin.tree

import com.ibrahimdans.i18n.plugin.key.lexer.Literal

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

    /**
     * The path a key missing below this node is written under, in this node's value, given what is
     * [unresolved] of it: [unresolved] itself, unless the node stands for keys written whole sharing a
     * prefix — a Symfony catalogue read flattened, where `flat.other` is one more key written whole
     * next to `flat.key`, not `other` nested in the file.
     */
    fun pathToCreate(unresolved: List<Literal>): List<Literal> = unresolved
}