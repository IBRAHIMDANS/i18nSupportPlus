package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.plugin.utils.PsiCodeNode
import com.intellij.psi.PsiElement

/**
 * Extractor of i18n key from PsiElement.
 *
 * It is also a [CodeKeyExtractor]: a [PsiCodeNode] is answered from the element it wraps, any other
 * [CodeNode] is not read — a PSI extractor knows only the PSI.
 */
interface KeyExtractor : CodeKeyExtractor {
    /**
     * Checks if current key extractor is applicable to given psi element
     */
    fun canExtract(element: PsiElement): Boolean

    /**
     * Extracts key from psi element
     */
    fun extract(element: PsiElement): RawKey

    override fun canExtract(node: CodeNode): Boolean = node is PsiCodeNode && canExtract(node.element)

    override fun extract(node: CodeNode): RawKey? =
        if (node is PsiCodeNode && canExtract(node.element)) extract(node.element) else null
}
