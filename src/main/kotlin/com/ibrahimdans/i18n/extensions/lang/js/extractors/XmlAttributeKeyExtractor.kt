package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.tree.ContextOption
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.nullableToList
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSDestructuringElement
import com.intellij.lang.javascript.psi.JSEmbeddedContent
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.patterns.XmlPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag

/**
 * Extracts the key of react-i18next's `<Trans i18nKey="…">`, read with the tag's other attributes
 * as react-i18next reads them:
 *  - `context`: `<Trans i18nKey="friend" context={gender}>` reads as `t('friend', { context: gender })`;
 *  - `ns`: `ns="auth"`, `ns={'auth'}` or `ns={['auth', 'common']}` reads as `t('key', { ns })`;
 *  - `t`: `<Trans t={t}>` with the `t` of `useTranslation('ns', { keyPrefix })` takes that hook's
 *    namespaces and key prefix, as a call of that `t` would. An `ns` attribute still wins.
 *
 * Without `ns` nor `t`, the key is in the default namespace: `<Trans>` does not take the namespace of
 * a `useTranslation` in the same component unless it is given its `t`.
 */
class XmlAttributeKeyExtractor: KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean = XmlPatterns.xmlAttributeValue("i18nKey").accepts(element)

    override fun extract(element: PsiElement): RawKey {
        val tag = (element.parent as? XmlAttribute)?.parent
        val hook = tag?.let(::hookOf)
        return RawKey(
            (element as? XmlAttributeValue)
                ?.value
                .nullableToList()
                .map {KeyElement.literal(it)},
            tag?.let(::namespacesOf).orEmpty().ifEmpty { hook?.arguments?.firstOrNull().let(OptionsExtractor::stringsOf) },
            hook?.let(::keyPrefixOf)?.let { listOf(KeyElement.literal(it)) }.orEmpty(),
            context = tag?.let(::contextOf),
        )
    }

    /**
     * The `context` attribute of [tag]: `context="male"` and `context={'male'}` name their variant,
     * any other expression is only known at runtime. Null without the attribute.
     */
    private fun contextOf(tag: XmlTag): ContextOption? {
        val attribute = tag.getAttribute(CONTEXT) ?: return null
        val value = attribute.valueElement ?: return ContextOption.Dynamic
        val embedded = embeddedOf(value) ?: return ContextOption.Fixed(value.value)
        val expression = PsiTreeUtil.getChildOfType(embedded, JSExpression::class.java)
        return if (expression is JSLiteralExpression && expression.isQuotedLiteral) expression.stringValue?.let(ContextOption::Fixed)
        else ContextOption.Dynamic
    }

    /** The namespaces [tag]'s `ns` attribute names: `ns="auth"`, `ns={'auth'}`, `ns={['auth', 'common']}`. */
    private fun namespacesOf(tag: XmlTag): List<String> {
        val value = tag.getAttribute(NS)?.valueElement ?: return emptyList()
        if (embeddedOf(value) == null) return listOf(value.value).filter { it.isNotBlank() }
        return OptionsExtractor.stringsOf(expressionOf(value))
    }

    /** The `useTranslation(…)` call whose `t` [tag]'s `t={t}` attribute passes, if any. */
    private fun hookOf(tag: XmlTag): JSCallExpression? {
        val value = tag.getAttribute(T)?.valueElement ?: return null
        val reference = expressionOf(value) as? JSReferenceExpression ?: return null
        val destructuring = PsiTreeUtil.getParentOfType(reference.resolve(), JSDestructuringElement::class.java)
        return (destructuring?.initializer as? JSCallExpression)?.takeIf { it.methodExpression?.text == USE_TRANSLATION }
    }

    /** The `keyPrefix` option of [hook], when it is a plain string. */
    private fun keyPrefixOf(hook: JSCallExpression): String? {
        val options = hook.arguments.getOrNull(1) as? JSObjectLiteralExpression
        val literal = options?.findProperty(KEY_PREFIX)?.value as? JSLiteralExpression
        return literal?.takeIf { it.isQuotedLiteral }?.stringValue?.takeIf { it.isNotBlank() }
    }

    /** The `{…}` of an attribute written `name={…}`; null for a plain `name="…"`. */
    private fun embeddedOf(value: XmlAttributeValue): PsiElement? = value.children.firstOrNull { it is JSEmbeddedContent }

    /** The JS expression of an attribute written `name={…}`; null for a plain `name="…"` or an empty `{}`. */
    private fun expressionOf(value: XmlAttributeValue): JSExpression? =
        embeddedOf(value)?.let { PsiTreeUtil.getChildOfType(it, JSExpression::class.java) }

    private companion object {
        const val CONTEXT = "context"
        const val NS = "ns"
        const val T = "t"
        const val KEY_PREFIX = "keyPrefix"
        const val USE_TRANSLATION = "useTranslation"
    }
}
