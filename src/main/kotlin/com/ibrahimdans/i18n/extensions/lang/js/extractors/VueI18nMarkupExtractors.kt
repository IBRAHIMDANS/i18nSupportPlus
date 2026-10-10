package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.type
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue

/**
 * vue-i18n's `<i18n-t keypath="terms.accept">` (or `<I18nT>`): the key is the `keypath` attribute.
 *
 * Only the static attribute: a bound `:keypath="expr"` is a JS expression, not read as a key.
 */
class VueI18nKeypathExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is XmlAttributeValue) return false
        val attribute = element.parent as? XmlAttribute ?: return false
        return attribute.name == "keypath" && attribute.parent?.name in TAGS && element.value.isNotBlank()
    }

    override fun extract(element: PsiElement): RawKey =
        RawKey(listOf(KeyElement.literal((element as XmlAttributeValue).value)))

    private companion object {
        val TAGS = setOf("i18n-t", "I18nT")
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
