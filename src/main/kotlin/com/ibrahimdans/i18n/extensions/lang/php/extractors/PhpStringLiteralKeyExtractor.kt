package com.ibrahimdans.i18n.extensions.lang.php.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.type
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.psi.PsiElement

/**
 * Extracts i18n key from js string literal
 */
class PhpStringLiteralKeyExtractor: KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean =
        listOf("quoted string").any{element.type().contains(it)}

    override fun extract(element: PsiElement): RawKey =
            RawKey(listOf(KeyElement.literal(element.text.unQuote())))
}