package com.ibrahimdans.i18n.extensions.localization.xliff

import com.ibrahimdans.i18n.plugin.tree.Tree
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * An XLIFF file as a translation tree: flat, one key per unit id, as Angular names its messages
 * (`@@home.title`). An id holding dots is one key, never a path: the code reads it whole.
 *
 * In a file of [role] [XliffRole.TARGET] a unit whose `<target>` is missing has no key yet — the
 * translation the locale lacks — while an empty `<target/>` is a key with an empty value.
 */
class XliffTree(private val file: PsiFile, private val role: XliffRole) : Tree<PsiElement> {

    override fun findChild(name: String): Tree<PsiElement>? = leafOf(XliffDocument.units(file)[name])

    override fun isTree(): Boolean = true

    override fun value(): PsiElement = file

    /** The ids starting with [prefix], as completion lists keys: each one's value is the `id` attribute's value. */
    override fun findChildren(prefix: String): List<Tree<PsiElement>> =
        XliffDocument.units(file).values
            .filter { it.id.startsWith(prefix) && it.valueTag(role) != null }
            .mapNotNull { unit -> unit.idValue?.let(::IdName) }

    override fun entries(): List<Pair<String, Tree<PsiElement>>> =
        XliffDocument.units(file).values.mapNotNull { unit -> leafOf(unit)?.let { unit.id to it } }

    private fun leafOf(unit: XliffUnit?): Tree<PsiElement>? =
        unit?.valueTag(role)?.let { Leaf(XliffDocument.valueElementOf(it)) }

    /** A unit's text: its plain text node, or the `<source>` / `<target>` tag holding markup or nothing. */
    private class Leaf(private val element: PsiElement) : Tree<PsiElement> {
        override fun findChild(name: String): Tree<PsiElement>? = null
        override fun isTree(): Boolean = false
        override fun value(): PsiElement = element
        override fun findChildren(prefix: String): List<Tree<PsiElement>> = emptyList()
    }

    /** A unit id as a key name, for completion: its value is the quoted attribute value. */
    private class IdName(private val idValue: PsiElement) : Tree<PsiElement> {
        override fun findChild(name: String): Tree<PsiElement>? = null
        override fun isTree(): Boolean = false
        override fun value(): PsiElement = idValue
        override fun findChildren(prefix: String): List<Tree<PsiElement>> = emptyList()
    }

    companion object {
        /** The tree of [file], its role read from its content; null for a file that is no XLIFF document. */
        fun of(file: PsiElement): XliffTree? {
            val psiFile = file as? PsiFile ?: file.containingFile ?: return null
            if (XliffDocument.rootOf(psiFile) == null) return null
            return XliffTree(psiFile, XliffDocument.roleOf(psiFile))
        }
    }
}
