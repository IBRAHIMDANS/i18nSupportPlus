package com.ibrahimdans.i18n.extensions.technology.symfony

import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.intellij.psi.PsiElement

/**
 * A Symfony catalogue read as Symfony reads it: flattened. `user.name` is both `name` nested under
 * `user:` and a key written `user.name` whole — `translation:extract` writes YAML flat by default, and
 * an XLIFF file is always flat. The key `user.name` reaches its segments one at a time, so a segment
 * missing as a child is looked up among the keys written whole, starting with it ([DottedPrefix]).
 */
internal class FlattenedTree(private val inner: Tree<PsiElement>, private val separator: String) : Tree<PsiElement> {

    override fun findChild(name: String): Tree<PsiElement>? =
        inner.findChild(name)?.let { FlattenedTree(it, separator) } ?: DottedPrefix.of(inner, name + separator, separator)

    override fun isTree(): Boolean = inner.isTree()

    override fun value(): PsiElement = inner.value()

    override fun findChildren(prefix: String): List<Tree<PsiElement>> = inner.findChildren(prefix)

    override fun entries(): List<Pair<String, Tree<PsiElement>>>? = inner.entries()?.map { (name, tree) -> name to FlattenedTree(tree, separator) }
}

/**
 * The keys of [owner] written whole that start with [prefix]: `user.name` and `user.email` under `user.`.
 * Its value is the owner's: a key created under it is written in the owner, whole ([pathToCreate]).
 */
private class DottedPrefix(private val owner: Tree<PsiElement>, private val prefix: String, private val separator: String) : Tree<PsiElement> {

    override fun findChild(name: String): Tree<PsiElement>? =
        owner.findChild(prefix + name)?.let { FlattenedTree(it, separator) } ?: of(owner, prefix + name + separator, separator)

    override fun isTree(): Boolean = true

    override fun value(): PsiElement = owner.value()

    /** Completion lists the keys written whole from the owner, as they are spelled there. */
    override fun findChildren(prefix: String): List<Tree<PsiElement>> = emptyList()

    /** A key missing under `flat.` is one more key written whole in the owner: `flat.other`. */
    override fun pathToCreate(unresolved: List<Literal>): List<Literal> =
        listOf(Literal(prefix + unresolved.joinToString(separator) { it.text }))

    override fun entries(): List<Pair<String, Tree<PsiElement>>>? =
        owner.entries()?.filter { it.first.startsWith(prefix) }?.map { (name, tree) -> name.removePrefix(prefix) to FlattenedTree(tree, separator) }

    companion object {
        /** The keys of [owner] starting with [prefix], or null when it holds none. */
        fun of(owner: Tree<PsiElement>, prefix: String, separator: String): Tree<PsiElement>? {
            if (separator.isEmpty()) return null
            val entries = owner.entries() ?: return null
            return if (entries.any { it.first.startsWith(prefix) }) DottedPrefix(owner, prefix, separator) else null
        }
    }
}
