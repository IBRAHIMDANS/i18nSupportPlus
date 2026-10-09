package com.ibrahimdans.i18n.extensions.lang.js

import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSDestructuringElement
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * The `t` a translation hook gives the functions enclosing an element — `const { t } =
 * useTranslation('account')` in a React component, `const { t } = useI18n()` in a Vue setup —
 * so that an extraction there writes `t('key')` rather than `i18n.t('key')`, which the component
 * does not import.
 *
 * Only a `t` destructured under its own name counts, and a react-i18next hook with a `keyPrefix`
 * does not: its `t` reads keys relative to the prefix, and the extracted key is a whole one.
 */
internal object TranslationHookInScope {

    /** What an unqualified `t('key')` resolves against: [namespaces] in order, the first by default. */
    data class Hook(val namespaces: List<String>)

    private val HOOKS = setOf("useTranslation", "useI18n")

    fun find(element: PsiElement): Hook? {
        var scope = PsiTreeUtil.getParentOfType(element, JSFunction::class.java)
        while (scope != null) {
            val owner = scope
            PsiTreeUtil.findChildrenOfType(owner, JSDestructuringElement::class.java)
                .asSequence()
                .filter { PsiTreeUtil.getParentOfType(it, JSFunction::class.java) == owner }
                .firstNotNullOfOrNull(::hookOf)
                ?.let { return it }
            scope = PsiTreeUtil.getParentOfType(owner, JSFunction::class.java)
        }
        return null
    }

    private fun hookOf(declaration: JSDestructuringElement): Hook? {
        val call = declaration.initializer as? JSCallExpression ?: return null
        if (call.methodExpression?.text !in HOOKS) return null
        if (PsiTreeUtil.findChildrenOfType(declaration, JSVariable::class.java).none { it.name == "t" }) return null
        val options = call.arguments.getOrNull(1) as? JSObjectLiteralExpression
        if (options?.findProperty("keyPrefix") != null) return null
        return Hook(call.arguments.firstOrNull()?.let(::stringsOf).orEmpty())
    }

    /** The quoted strings a hook argument carries: a single literal, or an array of them. */
    private fun stringsOf(argument: PsiElement): List<String> = when (argument) {
        is JSLiteralExpression -> if (argument.isQuotedLiteral) listOfNotNull(argument.stringValue) else emptyList()
        is JSArrayLiteralExpression ->
            argument.expressions.filterIsInstance<JSLiteralExpression>().filter { it.isQuotedLiteral }.mapNotNull { it.stringValue }
        else -> emptyList()
    }
}

/**
 * The function an extraction at [element] calls: the `t` of a translation hook in scope, else the
 * global `i18n.t`.
 */
internal fun translationFunction(element: PsiElement): String =
    if (TranslationHookInScope.find(element) != null) "t" else "i18n.t"
