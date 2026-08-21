package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Extracts the key from a react-intl message descriptor: `intl.formatMessage({ id: 'key' })`.
 *
 * The call is matched on the `formatMessage` suffix so every calling style is covered:
 * the bare `formatMessage(…)` destructured from `useIntl()`, the qualified
 * `intl.formatMessage(…)`, and `this.props.intl.formatMessage(…)` from injectIntl.
 * That suffix match is what makes this extractor necessary — `JsLang.isDirectOrConfiguredCall`
 * rejects qualified calls whose full text is not a configured translation function name.
 */
class ReactIntlExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is JSLiteralExpression || !element.isQuotedLiteral) return false
        if (!isInsideMessageDescriptor(element)) return false
        return (element.parent as? JSProperty)?.name == "id"
    }

    override fun extract(element: PsiElement): RawKey {
        val value = (element as JSLiteralExpression).stringValue ?: element.text.unQuote()
        return RawKey(listOf(KeyElement.literal(value)))
    }

    /**
     * True when [element] is a property value of the message descriptor passed as first
     * argument to a `formatMessage` call.
     *
     * Only `id` carries a translation key there — `defaultMessage` and `description` hold
     * source text. Callers use this to veto the generic string-literal extractors, which
     * would otherwise report the default message as an unresolved key.
     */
    fun isInsideMessageDescriptor(element: PsiElement): Boolean {
        val property = element.parent as? JSProperty ?: return false
        val objectLiteral = property.parent as? JSObjectLiteralExpression ?: return false
        // The object literal is not a direct child of the call: JS wraps call arguments
        // in a JSArgumentList.
        val callExpression = PsiTreeUtil.getParentOfType(objectLiteral, JSCallExpression::class.java) ?: return false
        if (callExpression.arguments.firstOrNull() !== objectLiteral) return false
        return callExpression.methodExpression?.text?.endsWith("formatMessage") == true
    }
}
