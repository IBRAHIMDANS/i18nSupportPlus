package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.psi.PsiElement

/**
 * A key as written at a call site.
 *
 * [keyPrefix] is what the call site's translation hook prepends and the literal does not write:
 * react-i18next's `useTranslation('ns', { keyPrefix: 'a.b' })`, next-intl's `useTranslations('A.B')`.
 * It is tokenized like the key itself, so it follows the configured key separator.
 */
data class RawKey(
    val keyElements: List<KeyElement>,
    val arguments: List<String> = emptyList(),
    val keyPrefix: List<KeyElement> = emptyList()
)

/**
 * Extractor of i18n key from PsiElement
 */
interface KeyExtractor {
    /**
     * Checks if current key extractor is applicable to given psi element
     */
    fun canExtract(element: PsiElement): Boolean

    /**
     * Extracts key from psi element
     */
    fun extract(element: PsiElement): RawKey
}