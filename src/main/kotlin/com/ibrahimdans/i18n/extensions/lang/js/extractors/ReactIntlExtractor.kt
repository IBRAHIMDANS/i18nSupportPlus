package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.rules.FrameworkPresets
import com.ibrahimdans.i18n.plugin.rules.KeyLocation
import com.ibrahimdans.i18n.plugin.rules.RuleKeyExtractor
import com.ibrahimdans.i18n.plugin.utils.PsiCodeNode
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.PsiElement

/**
 * Extracts the key from a react-intl message descriptor: `intl.formatMessage({ id: 'key' })`.
 *
 * The syntax is the `FrameworkPresets.REACT_INTL` descriptor rule, read by [RuleKeyExtractor]
 * through [PsiJsGrammar] — the same rule `i18n-check` reads on tree-sitter. It matches the call on
 * the `formatMessage` suffix so every calling style is covered: the bare `formatMessage(…)`
 * destructured from `useIntl()`, the qualified `intl.formatMessage(…)`, and
 * `this.props.intl.formatMessage(…)` from injectIntl. That suffix match is what makes this
 * extractor necessary — `JsLang.isDirectOrConfiguredCall` rejects qualified calls whose full text
 * is not a configured translation function name.
 */
class ReactIntlExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean = DESCRIPTOR.canExtract(PsiCodeNode(element))

    override fun extract(element: PsiElement): RawKey = DESCRIPTOR.extract(PsiCodeNode(element)) ?: RawKey(emptyList())

    /**
     * True when [element] is a property value of a react-intl message descriptor, whether it is
     * passed inline to `formatMessage` or declared in a `defineMessages` catalogue.
     *
     * Only `id` carries a translation key there — `defaultMessage` and `description` hold source
     * text. Callers use this to veto the generic string-literal extractors, which would otherwise
     * report the default message as an unresolved key.
     */
    fun isInsideMessageDescriptor(element: PsiElement): Boolean = ALL.isInsideDescriptor(PsiCodeNode(element))

    companion object {
        private val ALL = RuleKeyExtractor(FrameworkPresets.REACT_INTL, PsiJsGrammar)

        /** The inline descriptor rule alone: the catalogues are [DefineMessagesExtractor]'s, the component [FormattedMessageExtractor]'s. */
        private val DESCRIPTOR = RuleKeyExtractor(
            FrameworkPresets.REACT_INTL.copy(calls = FrameworkPresets.REACT_INTL.calls.filter { it.key is KeyLocation.Descriptor }, components = emptyList()),
            PsiJsGrammar,
        )

        /** True when [id] is the `id` of a descriptor passed inline to `formatMessage`. */
        fun isFormatMessageDescriptor(id: JSLiteralExpression): Boolean = DESCRIPTOR.canExtract(PsiCodeNode(id))
    }
}
