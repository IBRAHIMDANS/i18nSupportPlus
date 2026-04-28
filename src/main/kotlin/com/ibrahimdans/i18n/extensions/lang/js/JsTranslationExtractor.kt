package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.utils.type
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.Language
import com.intellij.lang.javascript.patterns.JSPatterns
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSThisExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

internal class JsTranslationExtractor: TranslationExtractor {
    override fun canExtract(element: PsiElement): Boolean {
        val jsLang = Language.findLanguageByID("JavaScript") ?: return false
        return "JS:STRING_LITERAL" == element.type()
                && element.containingFile.language.isKindOf(jsLang)
    }
    override fun isExtracted(element: PsiElement): Boolean {
        if (!JSPatterns.jsArgument("t", 0).accepts(element.parent)) return false
        return isDirectOrConfiguredCall(element)
    }
    override fun text(element: PsiElement): String = element.text.unQuote()

    private fun isDirectOrConfiguredCall(element: PsiElement): Boolean {
        val callExpr = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java) ?: return true
        val refExpr = callExpr.methodExpression as? JSReferenceExpression ?: return true
        val qualifier = refExpr.qualifier ?: return true
        if (qualifier is JSThisExpression) return true
        val fnNames = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
        return refExpr.text in fnNames
    }
}