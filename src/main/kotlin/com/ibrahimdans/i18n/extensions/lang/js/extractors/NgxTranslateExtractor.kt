package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.extensions.lang.js.AngularDirectiveExtractor
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
        // The literal's direct parent is the argument list, not the call itself.
        val call = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java) ?: return false
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

/**
 * The key of ngx-translate's attribute directive: `<p [translate]="'home.title'">`,
 * `<p translate="home.title">`, and the element's text under the bare directive, `<p translate>home.title</p>`.
 *
 * `translate` is also HTML's own attribute: its values `yes` and `no` are no keys.
 */
class NgxTranslateDirectiveExtractor : AngularDirectiveExtractor("translate", readsText = true) {
    override fun isKey(value: String): Boolean = super.isKey(value) && value !in HTML_VALUES

    private companion object {
        val HTML_VALUES = setOf("yes", "no")
    }
}
