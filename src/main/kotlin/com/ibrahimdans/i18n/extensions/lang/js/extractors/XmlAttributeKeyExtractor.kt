package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.tree.ContextOption
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.nullableToList
import com.intellij.lang.javascript.psi.JSEmbeddedContent
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.patterns.XmlPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue

/**
 * Extracts the key of react-i18next's `<Trans i18nKey="…">`, with the tag's `context` attribute:
 * `<Trans i18nKey="friend" context={gender}>` reads as `t('friend', { context: gender })`.
 */
class XmlAttributeKeyExtractor: KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean = XmlPatterns.xmlAttributeValue("i18nKey").accepts(element)

    override fun extract(element: PsiElement): RawKey =
        RawKey(
            (element as? XmlAttributeValue)
                ?.value
                .nullableToList()
                .map {KeyElement.literal(it)},
            context = contextOf(element),
        )

    /**
     * The `context` attribute of the tag holding [element]: `context="male"` and `context={'male'}`
     * name their variant, any other expression is only known at runtime. Null without the attribute.
     */
    private fun contextOf(element: PsiElement): ContextOption? {
        val attribute = (element.parent as? XmlAttribute)?.parent?.getAttribute(CONTEXT) ?: return null
        val value = attribute.valueElement ?: return ContextOption.Dynamic
        val embedded = value.children.firstOrNull { it is JSEmbeddedContent }
            ?: return ContextOption.Fixed(value.value)
        val expression = PsiTreeUtil.getChildOfType(embedded, JSExpression::class.java)
        return if (expression is JSLiteralExpression && expression.isQuotedLiteral) expression.stringValue?.let(ContextOption::Fixed)
        else ContextOption.Dynamic
    }

    private companion object {
        const val CONTEXT = "context"
    }
}
