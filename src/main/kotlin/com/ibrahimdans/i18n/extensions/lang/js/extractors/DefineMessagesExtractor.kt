package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.rules.FrameworkPresets
import com.ibrahimdans.i18n.plugin.rules.KeyLocation
import com.ibrahimdans.i18n.plugin.rules.RuleKeyExtractor
import com.ibrahimdans.i18n.plugin.utils.PsiCodeNode
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
 * The syntax is the `FrameworkPresets.REACT_INTL` catalogue rules, read by [RuleKeyExtractor]
 * through [PsiJsGrammar]. [ReactIntlExtractor] only sees the descriptor passed inline to
 * `formatMessage`, so a catalogue declared apart — the most common react-intl layout — had none
 * of its keys extracted: no annotation, no completion, no navigation.
 *
 * The indirect use site (`intl.formatMessage(messages.greeting)`) stays unannotated: it
 * would need to resolve the reference back to this declaration, which is a separate
 * concern. This extractor makes the declared ids themselves first-class keys.
 */
class DefineMessagesExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean = CATALOGUE.canExtract(PsiCodeNode(element))

    override fun extract(element: PsiElement): RawKey = CATALOGUE.extract(PsiCodeNode(element)) ?: RawKey(emptyList())

    private companion object {
        val CATALOGUE = RuleKeyExtractor(
            FrameworkPresets.REACT_INTL.copy(calls = FrameworkPresets.REACT_INTL.calls.filter { it.key is KeyLocation.Catalogue }, components = emptyList()),
            PsiJsGrammar,
        )
    }
}
