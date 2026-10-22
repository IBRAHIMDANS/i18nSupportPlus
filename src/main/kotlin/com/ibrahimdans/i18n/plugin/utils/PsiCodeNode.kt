package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * A [CodeNode] over the IDE's PSI: the node is [element], so two of them are equal when they
 * wrap the same element.
 *
 * Read lazily, like the PSI itself: each property is asked of [element] when read, under the read
 * access the caller already holds. The tree stops at the file: a [PsiFile] has no parent.
 */
class PsiCodeNode(val element: PsiElement) : CodeNode {

    override val type: String get() = element.node?.elementType?.toString() ?: element.javaClass.simpleName

    override val text: String get() = element.text

    override val span: TextSpan get() = element.textRange.let { TextSpan(it.startOffset, it.endOffset) }

    override val parent: CodeNode? get() = if (element is PsiFile) null else element.parent?.let(::PsiCodeNode)

    // Through the siblings rather than `children`: on a composite element `getChildren` leaves the
    // tokens out, and a key is often one of them.
    override val children: List<CodeNode>
        get() = generateSequence(element.firstChild) { it.nextSibling }.map(::PsiCodeNode).toList()

    override val file: String? get() = element.containingFile?.originalFile?.virtualFile?.path

    override fun equals(other: Any?): Boolean = other is PsiCodeNode && other.element == element

    override fun hashCode(): Int = element.hashCode()

    override fun toString(): String = "PsiCodeNode($type)"
}
