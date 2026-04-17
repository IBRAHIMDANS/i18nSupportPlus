package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

private val TRANSLATE_METHODS = setOf("instant", "get", "stream")

class NgxTranslateExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is JSLiteralExpression || !element.isQuotedLiteral) return false
        val call = element.parent as? JSCallExpression ?: return false
        if (call.arguments.firstOrNull() !== element) return false
        val methodExpr = PsiTreeUtil.getChildOfType(call, JSReferenceExpression::class.java) ?: return false
        if (methodExpr.referenceName !in TRANSLATE_METHODS) return false
        val qualifier = methodExpr.qualifier?.text ?: return false
        return qualifier.contains("translate", ignoreCase = true)
    }

    override fun extract(element: PsiElement): RawKey {
        val value = (element as? JSLiteralExpression)?.stringValue ?: return RawKey(emptyList())
        return RawKey(listOf(KeyElement.literal(value)))
    }
}
