package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Extracts i18n options from the second argument of a t() call.
 * Supports: { ns: 'namespace' } and { ns: ['ns1', 'ns2'] }
 */
object OptionsExtractor {
    fun extractNamespaces(element: PsiElement): List<String> {
        val callExpr = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java)
            ?: return emptyList()
        val optionsArg = callExpr.arguments.getOrNull(1) as? JSObjectLiteralExpression
            ?: return emptyList()
        val nsValue = optionsArg.findProperty("ns")?.value
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
}
