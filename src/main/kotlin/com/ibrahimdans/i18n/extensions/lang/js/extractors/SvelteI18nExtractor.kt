package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

private val SVELTE_I18N_FUNCTIONS = setOf("_", "\$_")

class SvelteI18nExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is JSLiteralExpression || !element.isQuotedLiteral) return false
        val call = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java) ?: return false
        return call.methodExpression?.text in SVELTE_I18N_FUNCTIONS
    }

    override fun extract(element: PsiElement): RawKey {
        val literal = element as? JSLiteralExpression ?: return RawKey(emptyList())
        val value = if (literal.isQuotedLiteral) literal.stringValue ?: "" else literal.text
        return RawKey(listOf(KeyElement.literal(value)))
    }
}
