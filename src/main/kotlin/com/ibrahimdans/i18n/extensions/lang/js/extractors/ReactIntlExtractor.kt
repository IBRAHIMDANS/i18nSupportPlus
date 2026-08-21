package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * PSI walk shared by every react-intl message descriptor syntax.
 *
 * A descriptor is an object literal where only `id` carries a translation key —
 * `defaultMessage` and `description` hold source text. Two syntaxes produce one:
 * the descriptor passed inline to `formatMessage`, and the descriptors declared in a
 * `defineMessages` / `defineMessage` catalogue. Keeping the walk here is what stops the
 * two extractors from re-deriving it, each with its own chance of getting the call
 * lookup wrong.
 */
internal object MessageDescriptors {

    private const val FORMAT_MESSAGE = "formatMessage"
    private val CATALOGUE_FUNCTIONS = setOf("defineMessages", "defineMessage")

    /** True when [element] is the value of a property named `id`. */
    fun isIdProperty(element: PsiElement): Boolean = (element.parent as? JSProperty)?.name == "id"

    /** The object literal holding [element] as one of its property values. */
    private fun owningObject(element: PsiElement): JSObjectLiteralExpression? =
        (element.parent as? JSProperty)?.parent as? JSObjectLiteralExpression

    /**
     * The call [obj] is the first argument of, or null.
     *
     * Call arguments are wrapped in a `JSArgumentList`, so the call is never the direct
     * parent — walking up with [PsiTreeUtil] instead of reading `parent` is the whole
     * point of this helper.
     */
    private fun callTakingAsFirstArgument(obj: JSObjectLiteralExpression): JSCallExpression? =
        PsiTreeUtil.getParentOfType(obj, JSCallExpression::class.java)
            ?.takeIf { it.arguments.firstOrNull() === obj }

    /**
     * Descriptor passed inline to a `formatMessage` call. The call is matched on its
     * suffix so every calling style is covered: the bare `formatMessage(…)` destructured
     * from `useIntl()`, the qualified `intl.formatMessage(…)`, and
     * `this.props.intl.formatMessage(…)` from injectIntl.
     */
    fun isFormatMessageDescriptor(element: PsiElement): Boolean {
        val descriptor = owningObject(element) ?: return false
        val call = callTakingAsFirstArgument(descriptor) ?: return false
        return call.methodExpression?.text?.endsWith(FORMAT_MESSAGE) == true
    }

    /**
     * Descriptor declared in a catalogue: `defineMessage({ id })` when the descriptor is
     * the argument itself, `defineMessages({ greeting: { id } })` when it sits one level
     * below it.
     */
    fun isCatalogueDescriptor(element: PsiElement): Boolean {
        val descriptor = owningObject(element) ?: return false
        callTakingAsFirstArgument(descriptor)?.let { return isCatalogueCall(it) }
        val catalogue = owningObject(descriptor) ?: return false
        val call = callTakingAsFirstArgument(catalogue) ?: return false
        return isCatalogueCall(call)
    }

    private fun isCatalogueCall(call: JSCallExpression): Boolean =
        (call.methodExpression as? JSReferenceExpression)?.referenceName in CATALOGUE_FUNCTIONS

    /**
     * True when [element] is a property value of any react-intl message descriptor.
     *
     * This is the veto used against the generic string-literal extractors: without it
     * they report `defaultMessage` and `description` as unresolved keys.
     */
    fun isInsideMessageDescriptor(element: PsiElement): Boolean =
        isFormatMessageDescriptor(element) || isCatalogueDescriptor(element)

    /** The key held by an `id` literal. */
    fun idRawKey(element: JSLiteralExpression): RawKey =
        RawKey(listOf(KeyElement.literal(element.stringValue ?: element.text.unQuote())))
}

/**
 * Extracts the key from a react-intl message descriptor: `intl.formatMessage({ id: 'key' })`.
 *
 * The call is matched on the `formatMessage` suffix so every calling style is covered:
 * the bare `formatMessage(…)` destructured from `useIntl()`, the qualified
 * `intl.formatMessage(…)`, and `this.props.intl.formatMessage(…)` from injectIntl.
 * That suffix match is what makes this extractor necessary — `JsLang.isDirectOrConfiguredCall`
 * rejects qualified calls whose full text is not a configured translation function name.
 */
class ReactIntlExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is JSLiteralExpression || !element.isQuotedLiteral) return false
        if (!MessageDescriptors.isIdProperty(element)) return false
        return MessageDescriptors.isFormatMessageDescriptor(element)
    }

    override fun extract(element: PsiElement): RawKey =
        MessageDescriptors.idRawKey(element as JSLiteralExpression)

    /**
     * True when [element] is a property value of a react-intl message descriptor, whether
     * it is passed inline to `formatMessage` or declared in a `defineMessages` catalogue.
     *
     * Only `id` carries a translation key there — `defaultMessage` and `description` hold
     * source text. Callers use this to veto the generic string-literal extractors, which
     * would otherwise report the default message as an unresolved key.
     */
    fun isInsideMessageDescriptor(element: PsiElement): Boolean =
        MessageDescriptors.isInsideMessageDescriptor(element)
}
