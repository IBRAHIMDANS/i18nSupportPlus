package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.rules.FrameworkPresets
import com.ibrahimdans.i18n.plugin.rules.RuleKeyExtractor
import com.ibrahimdans.i18n.plugin.utils.PsiCodeNode
import com.intellij.psi.PsiElement

/** react-intl's `<FormattedMessage id="key" />`: the component rule of `FrameworkPresets.REACT_INTL`, read through [PsiJsGrammar]. */
class FormattedMessageExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean = COMPONENT.canExtract(PsiCodeNode(element))

    override fun extract(element: PsiElement): RawKey = COMPONENT.extract(PsiCodeNode(element)) ?: RawKey(emptyList())

    private companion object {
        val COMPONENT = RuleKeyExtractor(FrameworkPresets.REACT_INTL.copy(calls = emptyList()), PsiJsGrammar)
    }
}
