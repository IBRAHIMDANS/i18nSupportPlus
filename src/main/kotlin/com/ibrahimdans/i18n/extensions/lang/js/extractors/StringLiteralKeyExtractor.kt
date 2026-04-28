package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.type
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.psi.PsiElement

/**
 * Extracts i18n key from js string literal
 */
class StringLiteralKeyExtractor: KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean =
        element.type() == "JS:STRING_LITERAL"

    override fun extract(element: PsiElement): RawKey =
        RawKey(
            listOf(KeyElement.literal(element.text.unQuote())),
            OptionsExtractor.extractNamespaces(element)
        )
}