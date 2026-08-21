package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.PsiElement

/**
 * Extracts the `id` of every descriptor declared in a react-intl catalogue:
 *
 * ```js
 * const messages = defineMessages({
 *   greeting: { id: 'app.greeting', defaultMessage: 'Hello' },
 * });
 * ```
 *
 * [ReactIntlExtractor] only sees the descriptor passed inline to `formatMessage`, so a
 * catalogue declared apart — the most common react-intl layout — had none of its keys
 * extracted: no annotation, no completion, no navigation.
 *
 * The indirect use site (`intl.formatMessage(messages.greeting)`) stays unannotated: it
 * would need to resolve the reference back to this declaration, which is a separate
 * concern. This extractor makes the declared ids themselves first-class keys.
 */
class DefineMessagesExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is JSLiteralExpression || !element.isQuotedLiteral) return false
        if (!MessageDescriptors.isIdProperty(element)) return false
        return MessageDescriptors.isCatalogueDescriptor(element)
    }

    override fun extract(element: PsiElement): RawKey =
        MessageDescriptors.idRawKey(element as JSLiteralExpression)
}
