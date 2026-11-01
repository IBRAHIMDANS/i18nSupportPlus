package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.tree.ContextOption
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Extracts i18n options from the second argument of a t() call.
 * Supports: { ns: 'namespace' }, { ns: ['ns1', 'ns2'] } and { context: … }
 */
object OptionsExtractor {
    fun extractNamespaces(element: PsiElement): List<String> {
        val nsValue = optionsOf(element)?.findProperty("ns")?.value
            ?: return emptyList()
        return when {
            nsValue is JSLiteralExpression && nsValue.isQuotedLiteral ->
                listOfNotNull(nsValue.stringValue)
            nsValue is JSArrayLiteralExpression ->
                nsValue.expressions
                    .filterIsInstance<JSLiteralExpression>()
                    .filter { it.isQuotedLiteral }
                    .mapNotNull { it.stringValue }
            else -> emptyList()
        }
    }

    /**
     * i18next's `context` option: `{ context: 'male' }` names its variant, anything else —
     * `{ context: gender }`, the shorthand `{ context }`, a template — is only known at runtime.
     * Null when the call passes none.
     */
    fun extractContext(element: PsiElement): ContextOption? {
        val property = optionsOf(element)?.findProperty("context") ?: return null
        val value = property.value
        return if (value is JSLiteralExpression && value.isQuotedLiteral) value.stringValue?.let(ContextOption::Fixed)
        else ContextOption.Dynamic
    }

    /** The options object [element]'s call passes second: `t('key', { … })`. */
    private fun optionsOf(element: PsiElement): JSObjectLiteralExpression? =
        PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java)
            ?.arguments?.getOrNull(1) as? JSObjectLiteralExpression
}
