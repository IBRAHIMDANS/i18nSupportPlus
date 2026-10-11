package com.ibrahimdans.i18n.extensions.localization.php

import com.ibrahimdans.i18n.plugin.tree.Tree
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.parser.PhpElementTypes
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression
import com.jetbrains.php.lang.psi.elements.ArrayHashElement
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.PhpPsiElement
import com.jetbrains.php.lang.psi.elements.PhpReturn
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/**
 * Tree wrapper around the array a PHP translation file returns: `return ['welcome' => 'Welcome'];`
 * or `return array(...)`, nested arrays being the nested keys.
 *
 * Only string keys are keys: an entry with an integer key, or no key at all, is not a translation.
 */
class PhpArrayTree(val element: PsiElement) : Tree<PsiElement> {
    override fun value(): PsiElement = element
    override fun isTree(): Boolean = element is ArrayCreationExpression

    override fun findChild(name: String): Tree<PsiElement>? =
        namedEntries().firstOrNull { (key, _) -> key.contents == name }
            ?.second?.let { PhpArrays.valueOf(it) }?.let(::PhpArrayTree)

    // Key-literal wrappers, as the other formats return them: callers read `value().text.unQuote()`.
    override fun findChildren(prefix: String): List<Tree<PsiElement>> =
        namedEntries().filter { (key, _) -> key.contents.startsWith(prefix) }.map { (key, _) -> PhpArrayTree(key) }

    override fun entries(): List<Pair<String, Tree<PsiElement>>> =
        namedEntries().mapNotNull { (key, entry) -> PhpArrays.valueOf(entry)?.let { key.contents to PhpArrayTree(it) } }

    private fun namedEntries(): List<Pair<StringLiteralExpression, ArrayHashElement>> =
        (element as? ArrayCreationExpression)?.hashElements
            ?.mapNotNull { entry -> PhpArrays.keyOf(entry)?.let { it to entry } }
            ?: emptyList()

    companion object {
        /** The tree of the array [file] returns, or null when it returns none: a PHP file of code. */
        fun create(file: PsiElement): PhpArrayTree? = PhpArrays.rootArray(file)?.let(::PhpArrayTree)
    }
}

/** Reads the PSI of a PHP translation file. */
internal object PhpArrays {

    /**
     * The array returned at the top level of [file]: the `return` of a closure or a function
     * declared in the file is not the file's.
     */
    fun rootArray(file: PsiElement): ArrayCreationExpression? {
        val psiFile = file as? PsiFile ?: return null
        return PsiTreeUtil.findChildrenOfType(psiFile, PhpReturn::class.java)
            .firstOrNull { PsiTreeUtil.getParentOfType(it, Function::class.java) == null }
            ?.argument as? ArrayCreationExpression
    }

    /** The string key of [entry], or null for an integer or computed key. */
    fun keyOf(entry: ArrayHashElement): StringLiteralExpression? = unwrap(entry.key) as? StringLiteralExpression

    /** The value of [entry]: a string literal, a nested array, or any other expression. */
    fun valueOf(entry: ArrayHashElement): PsiElement? = unwrap(entry.value)

    /** The expression an `ARRAY_KEY` / `ARRAY_VALUE` wrapper holds; any other element as it is. */
    private fun unwrap(element: PsiElement?): PsiElement? {
        if (element !is PhpPsiElement) return element
        val type = element.node.elementType
        return if (type == PhpElementTypes.ARRAY_KEY || type == PhpElementTypes.ARRAY_VALUE) element.firstPsiChild else element
    }

    /**
     * The key path of [key], a key literal of a translation array, from the file's root array down;
     * null when [key] is not one: a key of an array passed to a call, or of a file returning nothing.
     */
    fun pathOf(key: StringLiteralExpression): List<String>? {
        val path = mutableListOf<String>()
        var literal = key
        while (true) {
            val entry = literal.parent?.parent as? ArrayHashElement ?: return null
            if (keyOf(entry) != literal) return null
            path.add(literal.contents)
            val array = entry.parent as? ArrayCreationExpression ?: return null
            if (array == rootArray(array.containingFile)) return path.reversed()
            // The array is the value of an entry of its parent array, else it is no translation.
            val parentEntry = array.parent?.parent as? ArrayHashElement ?: return null
            if (valueOf(parentEntry) != array) return null
            literal = keyOf(parentEntry) ?: return null
        }
    }
}
