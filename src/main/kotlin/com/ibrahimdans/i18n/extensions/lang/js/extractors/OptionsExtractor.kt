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
    fun extractNamespaces(element: PsiElement): List<String> =
        stringsOf(optionsOf(element)?.findProperty("ns")?.value)

    /**
     * The namespaces an `ns` value names: `'auth'` or `['auth', 'common']`, quoted literals only.
     * Empty for any other expression, known only at runtime.
     */
    fun stringsOf(value: PsiElement?): List<String> = when {
        value is JSLiteralExpression && value.isQuotedLiteral -> listOfNotNull(value.stringValue)
        value is JSArrayLiteralExpression ->
            value.expressions
                .filterIsInstance<JSLiteralExpression>()
                .filter { it.isQuotedLiteral }
                .mapNotNull { it.stringValue }
        else -> emptyList()
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
