package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.extensions.lang.js.importsPackage
import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * svelte-i18n's message stores: `$_('key')`, its aliases `$t` and `$format`, and `$json('nav')`,
 * which returns the messages under a key rather than one of them. Outside a component the store is
 * unwrapped under the same name: `const $_ = unwrapFunctionStore(_)`.
 *
 * The key is the call's first argument, or the `id` of the descriptor passed in its place —
 * `$_({ id: 'key', values: { name } })`. The `values` and `default` of either form hold no key.
 */
class SvelteI18nExtractor(private val functions: Set<String> = ALL) : KeyExtractor {

    companion object {
        /** `$json(key)` returns the messages under the key: it names an object as well as a leaf. */
        const val JSON = "\$json"

        val ALL = setOf("_", "\$_", "\$t", "\$format", JSON)

        /** Names other libraries call too: svelte-i18n's only once the file imports it. `_` is lodash's, `$t` vue-i18n's. */
        private val IMPORT_GATED = setOf("_", "\$t")

        private const val PACKAGE = "svelte-i18n"
        private const val ID = "id"
    }

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is JSLiteralExpression || !element.isQuotedLiteral) return false
        return messageCallOf(descriptorOf(element) ?: element) != null
    }

    override fun extract(element: PsiElement): RawKey {
        val literal = element as? JSLiteralExpression ?: return RawKey(emptyList())
        val value = if (literal.isQuotedLiteral) literal.stringValue ?: "" else literal.text
        return RawKey(listOf(KeyElement.literal(value)))
    }

    /**
     * True for a literal of a descriptor passed to a message store other than its `id`, or for its
     * string token: the generic extraction reads every string of a first-argument object, `default`
     * included.
     */
    fun isInsideDescriptorBesidesId(element: PsiElement): Boolean {
        val literal = element as? JSLiteralExpression ?: element.parent as? JSLiteralExpression ?: return false
        val argument = PsiTreeUtil.findFirstParent(literal) { it.parent is JSArgumentList } as? JSObjectLiteralExpression
            ?: return false
        return descriptorOf(literal) !== argument && messageCallOf(argument) != null
    }

    /** The descriptor [literal] is the `id` of, or null. */
    private fun descriptorOf(literal: JSLiteralExpression): JSObjectLiteralExpression? =
        (literal.parent as? JSProperty)?.takeIf { it.name == ID }?.parent as? JSObjectLiteralExpression

    /** The message store call taking [argument] first, or null. */
    private fun messageCallOf(argument: PsiElement): JSCallExpression? {
        val arguments = argument.parent as? JSArgumentList ?: return null
        if (arguments.arguments.firstOrNull() !== argument) return null
        val call = arguments.parent as? JSCallExpression ?: return null
        val name = call.methodExpression?.text ?: return null
        if (name !in functions) return null
        return call.takeIf { name !in IMPORT_GATED || importsPackage(PACKAGE, argument) }
    }
}
