package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

private val TRANSLOCO_METHODS = setOf("translate", "selectTranslate")

/**
 * The key of a Transloco service call: `this.translocoService.translate('key')`,
 * `transloco.selectTranslate('key')`.
 *
 * Like [NgxTranslateExtractor], the call is told apart by its qualifier, which must name the
 * service (`transloco` in it, any case): `translate` alone is too common a method name to claim.
 */
class TranslocoExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is JSLiteralExpression || !element.isQuotedLiteral) return false
        val call = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java) ?: return false
        if (call.arguments.firstOrNull() !== element) return false
        val method = call.methodExpression as? JSReferenceExpression ?: return false
        if (method.referenceName !in TRANSLOCO_METHODS) return false
        val qualifier = method.qualifier?.text ?: return false
        return qualifier.contains("transloco", ignoreCase = true)
    }

    override fun extract(element: PsiElement): RawKey {
        val value = (element as? JSLiteralExpression)?.stringValue ?: return RawKey(emptyList())
        return RawKey(listOf(KeyElement.literal(value)))
    }
}

/**
 * The key of `{{ 'key' | transloco }}` in an Angular template, recognised like
 * [AngularTranslatePipeExtractor] recognises `| translate`: on the text of the two closest
 * ancestors, with no compile-time dependency on the Angular plugin.
 */
class TranslocoPipeExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is JSLiteralExpression || !element.isStringLiteral) return false
        return generateSequence(element.parent) { it.parent }
            .take(2)
            .any { PIPE.containsMatchIn(it.text) }
    }

    override fun extract(element: PsiElement): RawKey =
        RawKey(listOf(KeyElement.literal(element.text.trim().removeSurrounding("\"").removeSurrounding("'"))))

    private companion object {
        val PIPE = Regex("""\|\s*transloco\b""")
    }
}
