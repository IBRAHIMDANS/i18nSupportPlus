package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.type
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue

/**
 * vue-i18n's `<i18n-t keypath="terms.accept">` (or `<I18nT>`): the key is the `keypath` attribute.
 *
 * The static attribute is read on its [XmlAttributeValue]. A bound `:keypath` / `v-bind:keypath`
 * is parsed in place by the Vue plugin, like `v-t`: it is read on its JS literal, and only when the
 * whole expression is a constant string — `'…'`, `"…"` or a template literal without
 * interpolation. `:keypath="variable"` or `:keypath="'a.' + x"` stays out.
 */
class VueI18nKeypathExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean = when (element) {
        is XmlAttributeValue -> {
            val attribute = element.parent as? XmlAttribute
            attribute != null && attribute.name == "keypath" && attribute.parent?.name in TAGS && element.value.isNotBlank()
        }
        else -> boundKey(element)?.isNotBlank() == true
    }

    override fun extract(element: PsiElement): RawKey {
        val key = if (element is XmlAttributeValue) element.value else boundKey(element).orEmpty()
        return RawKey(listOf(KeyElement.literal(key)))
    }

    /** The key a bound `:keypath`'s constant string literal holds, or null when [element] is not one. */
    private fun boundKey(element: PsiElement): String? {
        val key = when (element) {
            is JSStringTemplateExpression -> if (element.arguments.isEmpty()) element.text.unQuote() else null
            is JSLiteralExpression -> if (element.isQuotedLiteral) element.text.unQuote() else null
            else -> null
        } ?: return null
        // The literal must be the whole expression: the Vue plugin's wrapper is its direct parent.
        if (element.parent?.type()?.contains(EMBEDDED_CONTENT) != true) return null
        val attribute = PsiTreeUtil.getParentOfType(element, XmlAttribute::class.java) ?: return null
        return if (attribute.name in BOUND_NAMES && attribute.parent?.name in TAGS) key else null
    }

    private companion object {
        val TAGS = setOf("i18n-t", "I18nT")
        val BOUND_NAMES = setOf(":keypath", "v-bind:keypath")
        const val EMBEDDED_CONTENT = "EMBEDDED_EXPR_CONTENT"
    }
}

/**
 * vue-i18n's `v-t` directive: `v-t="'home.title'"`, or the `path` of an object,
 * `v-t="{ path: 'home.greeting', args: { name } }"`.
 *
 * The directive value is parsed in place by the Vue plugin, not injected: the literal sits in the
 * template's own PSI, under the attribute. Only a literal that *is* the value, or the `path` of an
 * object that is, counts — `v-t="cond ? 'a' : 'b'"` or the strings of `args` do not.
 */
class VueTDirectiveExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is JSLiteralExpression || !element.isQuotedLiteral) return false
        val value = when (val parent = element.parent) {
            is JSProperty -> if (parent.name == "path" && parent.value === element) parent.parent as? JSObjectLiteralExpression else null
            else -> element
        } ?: return false
        // The Vue plugin's expression wrapper, named by its type to stay free of its classes.
        if (value.parent?.type()?.contains(EMBEDDED_CONTENT) != true) return false
        return PsiTreeUtil.getParentOfType(value, XmlAttribute::class.java)?.name == "v-t"
    }

    override fun extract(element: PsiElement): RawKey =
        RawKey(listOf(KeyElement.literal(element.text.unQuote())))

    private companion object {
        const val EMBEDDED_CONTENT = "EMBEDDED_EXPR_CONTENT"
    }
}
