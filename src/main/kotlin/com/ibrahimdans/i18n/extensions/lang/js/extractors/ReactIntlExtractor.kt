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

class ReactIntlExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is JSLiteralExpression || !element.isQuotedLiteral) return false
        val property = element.parent as? JSProperty ?: return false
        if (property.name != "id") return false
        val objectLiteral = property.parent as? JSObjectLiteralExpression ?: return false
        val callExpression = objectLiteral.parent as? JSCallExpression ?: return false
        val firstArg = callExpression.arguments.firstOrNull() ?: return false
        if (firstArg !== objectLiteral) return false
        return callExpression.methodExpression?.text?.endsWith("formatMessage") == true
    }

    override fun extract(element: PsiElement): RawKey {
        val value = (element as JSLiteralExpression).stringValue ?: element.text.unQuote()
        return RawKey(listOf(KeyElement.literal(value)))
    }
}
