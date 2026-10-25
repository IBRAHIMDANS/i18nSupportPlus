package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.hostFile
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement

/**
 * The key of vue-i18n's Composition API: `const { t, te } = useI18n()` in `<script setup>` or
 * `setup()`, then `t('key')` or `te('key')` in the script or the component's template.
 *
 * `t` is i18next's name too, and a module on the vue-i18n preset reads vue-i18n's calls only — so
 * every `t('…')` of a Composition API component went unread there. It is read here, by vue-i18n's
 * own syntax: only in a file that destructures that name from `useI18n()`, so a `t` of another
 * origin stays out of a vue-i18n module.
 */
class VueI18nComposableExtractor : KeyExtractor {

    private companion object {
        val FUNCTIONS = setOf("t", "te")
        val DESTRUCTURING = FUNCTIONS.associateWith { name -> Regex("""\{[^}]*\b$name\b[^}]*}\s*=\s*useI18n\s*\(""") }
    }

    override fun canExtract(element: PsiElement): Boolean {
        val literal = element as? JSLiteralExpression ?: element.parent as? JSLiteralExpression ?: return false
        if (!literal.isQuotedLiteral) return false
        val arguments = literal.parent as? JSArgumentList ?: return false
        if (arguments.arguments.firstOrNull() !== literal) return false
        val callee = (arguments.parent as? JSCallExpression)?.methodExpression as? JSReferenceExpression ?: return false
        if (callee.qualifier != null) return false
        val destructuring = DESTRUCTURING[callee.referenceName] ?: return false
        return literal.hostFile()?.text?.let(destructuring::containsMatchIn) == true
    }

    override fun extract(element: PsiElement): RawKey {
        val literal = element as? JSLiteralExpression ?: element.parent as JSLiteralExpression
        return RawKey(listOfNotNull(literal.stringValue?.let(KeyElement::literal)))
    }
}
