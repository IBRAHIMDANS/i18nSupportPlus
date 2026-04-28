package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.utils.toBoolean
import com.intellij.lang.Language
import com.intellij.lang.javascript.patterns.JSPatterns
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSThisExpression
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag

internal class JsxTranslationExtractor : TranslationExtractor {
    override fun canExtract(element: PsiElement): Boolean {
        val fileType = element.containingFile.fileType
        val fileName = element.containingFile.name
        val isJsxFile = fileType.name.contains("JSX", ignoreCase = true) ||
            fileName.endsWith(".jsx", ignoreCase = true) ||
            fileName.endsWith(".tsx", ignoreCase = true)

        return isJsxFile && PsiTreeUtil.getParentOfType(element, XmlTag::class.java)?.let {
            !PsiTreeUtil.findChildOfType(it, XmlTag::class.java).toBoolean()
        } ?: false
    }

    override fun isExtracted(element: PsiElement): Boolean {
        if (!element.isJs()) return false
        if (!JSPatterns.jsArgument("t", 0).accepts(element.parent)) return false
        return isDirectOrConfiguredCall(element)
    }

    private fun isDirectOrConfiguredCall(element: PsiElement): Boolean {
        val callExpr = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java) ?: return true
        val refExpr = callExpr.methodExpression as? JSReferenceExpression ?: return true
        val qualifier = refExpr.qualifier ?: return true
        if (qualifier is JSThisExpression) return true
        val fnNames = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
        return refExpr.text in fnNames
    }

    override fun text(element: PsiElement): String {
        if (element.parent is XmlAttributeValue) return element.text
        return PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
            ?.value
            ?.textElements
            ?.joinToString(" ") { it.text }
            ?: element.text
    }

    override fun textRange(element: PsiElement): TextRange {
        if (element.parent is XmlAttributeValue) return element.parent.textRange
        val textElements = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
            ?.value?.textElements ?: return element.textRange
        if (textElements.isEmpty()) return element.textRange
        return TextRange(textElements.first().textRange.startOffset, textElements.last().textRange.endOffset)
    }

    override fun template(element: PsiElement): (argument: String) -> String = {
        "{i18n.t($it)}"
    }
    private fun PsiElement.isJs(): Boolean {
        val jsLang = Language.findLanguageByID("JavaScript") ?: return false
        return this.language.isKindOf(jsLang)
    }
}
