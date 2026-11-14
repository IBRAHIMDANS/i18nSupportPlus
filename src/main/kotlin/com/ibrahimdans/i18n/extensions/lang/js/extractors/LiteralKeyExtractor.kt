package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.lang.javascript.psi.JSIndexedPropertyAccessExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralValue
import com.intellij.psi.xml.XmlElementType
/**
 * Extracts translation key from psi literal
 */
class LiteralKeyExtractor: KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean =
        element is PsiLiteralValue && element.node.elementType != XmlElementType.XML_ATTRIBUTE_VALUE && !isMemberIndex(element)

    override fun extract(element: PsiElement): RawKey =
        RawKey(
            (element as PsiLiteralValue).value?.let { it as? String }?.let {
                listOf(KeyElement.literal(it))
            } ?: listOf(),
            OptionsExtractor.extractNamespaces(element),
            context = OptionsExtractor.extractContext(element),
        )
}

/**
 * Whether [element], a string literal or its token, is the index of a member access, `'save'` of
 * `t(KEYS['save'])`: the name of a property the code reads, never a key of its own.
 */
internal fun isMemberIndex(element: PsiElement): Boolean {
    val literal = element as? JSLiteralExpression ?: element.parent as? JSLiteralExpression ?: return false
    return (literal.parent as? JSIndexedPropertyAccessExpression)?.indexExpression === literal
}
