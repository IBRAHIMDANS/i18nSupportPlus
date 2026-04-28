package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSDestructuringElement
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Extracts i18n key from js string literal
 */
class ReactUseTranslationHookExtractor: KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is JSLiteralExpression || !element.isQuotedLiteral) return false
        return resolveHook(element)?.methodExpression?.text == "useTranslation"
    }

    override fun extract(element: PsiElement): RawKey {
        val arguments = resolveHook(element)?.arguments
            ?.flatMap { arg ->
                when (arg) {
                    is JSLiteralExpression ->
                        if (arg.isQuotedLiteral) listOfNotNull(arg.stringValue) else emptyList()
                    is JSArrayLiteralExpression ->
                        arg.expressions
                            .filterIsInstance<JSLiteralExpression>()
                            .filter { it.isQuotedLiteral }
                            .mapNotNull { it.stringValue }
                    else -> emptyList()
                }
            } ?: listOf()
        return RawKey(listOf(KeyElement.literal(element.text.unQuote())), arguments)
    }

    private fun resolveTranslationFunctionDefinition(element: PsiElement): PsiElement? {
        return PsiTreeUtil
            .getChildOfType(
                PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java),
                JSReferenceExpression::class.java
            )
            ?.reference
            ?.resolve()
    }

    private fun resolveDestructuringElement(t: PsiElement?): JSDestructuringElement? {
        return PsiTreeUtil.getParentOfType(t, JSDestructuringElement::class.java)
    }

    private fun resolveHook(literal: PsiElement): JSCallExpression? {
        // Primary: follow reference to local definition
        val viaRef = resolveTranslationFunctionDefinition(literal)
            ?.let { resolveDestructuringElement(it) }
            ?.let { it.initializer as? JSCallExpression }
        if (viaRef != null) return viaRef

        // Fallback: scope walk when reference resolution goes to type declarations
        return resolveHookViaScopeWalk(literal)
    }

    private fun resolveHookViaScopeWalk(literal: PsiElement): JSCallExpression? {
        val tCall = PsiTreeUtil.getParentOfType(literal, JSCallExpression::class.java) ?: return null
        val fnName = PsiTreeUtil.getChildOfType(tCall, JSReferenceExpression::class.java)?.text ?: return null
        val scope = PsiTreeUtil.getParentOfType(tCall, JSFunction::class.java) ?: return null
        val namePattern = Regex("\\b${Regex.escape(fnName)}\\b")
        return PsiTreeUtil.findChildrenOfType(scope, JSDestructuringElement::class.java)
            .firstOrNull { elem ->
                (elem.initializer as? JSCallExpression)?.methodExpression?.text == "useTranslation"
                    && namePattern.containsMatchIn(elem.text)
            }
            ?.let { it.initializer as? JSCallExpression }
    }
}