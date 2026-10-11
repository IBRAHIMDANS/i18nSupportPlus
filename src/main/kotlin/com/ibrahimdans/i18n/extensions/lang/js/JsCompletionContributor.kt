package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.ide.completion.CompositeKeyCompletionContributor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttributeValue

/**
 * Completion contributor for JS dialects
 */
class JsCompletionContributor: CompositeKeyCompletionContributor(CaretJsLang())

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
