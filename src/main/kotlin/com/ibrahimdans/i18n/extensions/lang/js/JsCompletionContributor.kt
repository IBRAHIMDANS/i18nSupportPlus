package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.NextIntlMethodExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.XmlAttributeKeyExtractor
import com.ibrahimdans.i18n.plugin.ide.completion.CompositeKeyCompletionContributor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.type
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue

/**
 * Completion contributor for JS dialects
 */
class JsCompletionContributor: JsKeyCompletionContributor(CaretJsLang())

/** Completion of the keys [lang] reads, in string literals as in markup. */
abstract class JsKeyCompletionContributor(lang: JsLang) : CompositeKeyCompletionContributor(lang) {

    /**
     * Only string literals are read whatever holds them: markup extractors recognise their own syntax.
     * A literal also completes where the annotator checks nothing: `t.has('key')`, `i18nKey={'key'}`.
     */
    override fun isKeyPosition(position: PsiElement): Boolean {
        val literal = position.parent as? JSLiteralExpression
        // The path of `import … from '…'` is a string token of the clause itself, under no literal.
            ?: return position.type() != "JS:STRING_LITERAL"
        return super.isKeyPosition(position)
            || NEXT_INTL_HAS.canExtract(literal)
            || PsiTreeUtil.getParentOfType(literal, XmlAttributeValue::class.java)?.let(I18N_KEY_ATTRIBUTE::canExtract) == true
    }

    private companion object {
        val NEXT_INTL_HAS = NextIntlMethodExtractor(setOf(NextIntlMethodExtractor.HAS))
        val I18N_KEY_ATTRIBUTE = XmlAttributeKeyExtractor()
    }
}

/**
 * [JsLang] asked about the completion caret, which sits on a leaf token.
 *
 * A JS string literal is its own leaf, but an attribute key — vue-i18n's `<i18n-t keypath="…">`,
 * react-i18next's `i18nKey="…"` — is held by the [XmlAttributeValue] above the caret's token.
 * Asked of the token alone, nothing was extracted and completion offered the root keys whatever
 * was typed, so `keypath="common:user.<caret>"` listed nothing.
 */
private class CaretJsLang : JsLang() {
    override fun extractRawKey(element: PsiElement): RawKey? =
        super.extractRawKey(element) ?: (element.parent as? XmlAttributeValue)?.let { super.extractRawKey(it) }
}
