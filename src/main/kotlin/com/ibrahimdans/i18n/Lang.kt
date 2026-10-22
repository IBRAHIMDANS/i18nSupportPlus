package com.ibrahimdans.i18n

import com.ibrahimdans.i18n.plugin.factory.FoldingProvider
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.PsiCodeNode
import com.intellij.psi.PsiElement

/**
 * A source language the plugin reads keys from.
 *
 * It is also a [CodeLang]: a [PsiCodeNode] is answered from the element it wraps, any other
 * [CodeNode] holds no key for a language that reads the PSI.
 */
interface Lang : CodeLang {

    fun canExtractKey(element: PsiElement, translationFunctionNames: List<String>): Boolean

    fun extractRawKey(element: PsiElement): RawKey?

    /**
     * Get folding provider
     */
    fun foldingProvider(): FoldingProvider

    /**
     * Get translation extractor object
     */
    fun translationExtractor(): TranslationExtractor

    fun resolveLiteral(entry: PsiElement): PsiElement?

    override fun canExtractKey(node: CodeNode, translationFunctionNames: List<String>): Boolean =
        node is PsiCodeNode && canExtractKey(node.element, translationFunctionNames)

    override fun extractRawKey(node: CodeNode): RawKey? = (node as? PsiCodeNode)?.let { extractRawKey(it.element) }
}
