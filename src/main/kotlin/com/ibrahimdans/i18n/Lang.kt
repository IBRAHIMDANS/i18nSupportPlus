package com.ibrahimdans.i18n

import com.ibrahimdans.i18n.plugin.factory.FoldingProvider
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.intellij.psi.PsiElement

interface Lang {

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
}