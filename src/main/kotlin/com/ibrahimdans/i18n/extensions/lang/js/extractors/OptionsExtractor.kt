package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.tree.ContextOption
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Extracts i18n options from a t() call's options object.
 * Supports: { ns: 'namespace' }, { ns: ['ns1', 'ns2'] }, { context: … }, { returnObjects: true } and
 * { defaultValue: '…' } — and the fallback keys of `t(['specific', 'generic'])`.
 */
object OptionsExtractor {
    fun extractNamespaces(element: PsiElement): List<String> =
        stringsOf(optionsOf(element)?.findProperty("ns")?.value)

    /**
     * The namespaces an `ns` value names: `'auth'` or `['auth', 'common']`, quoted literals only.
     * Empty for any other expression, known only at runtime.
     */
    fun stringsOf(value: PsiElement?): List<String> = when {
        value is JSLiteralExpression && value.isQuotedLiteral -> listOfNotNull(value.stringValue)
        value is JSArrayLiteralExpression ->
            value.expressions
                .filterIsInstance<JSLiteralExpression>()
                .filter { it.isQuotedLiteral }
                .mapNotNull { it.stringValue }
        else -> emptyList()
    }

    /**
     * i18next's `context` option: `{ context: 'male' }` names its variant, anything else —
     * `{ context: gender }`, the shorthand `{ context }`, a template — is only known at runtime.
     * Null when the call passes none.
     */
    fun extractContext(element: PsiElement): ContextOption? {
        val property = optionsOf(element)?.findProperty("context") ?: return null
        val value = property.value
        return if (value is JSLiteralExpression && value.isQuotedLiteral) value.stringValue?.let(ContextOption::Fixed)
        else ContextOption.Dynamic
    }

    /**
     * i18next's `returnObjects: true`: the call reads the object or array under its key, so a key
     * pointing at one is what it asks for. The literal `true` only: `false` or a variable keeps the
     * warning, a string being what the call may return then.
     */
    fun returnsObjects(element: PsiElement): Boolean {
        val value = optionsOf(element)?.findProperty("returnObjects")?.value
        return value is JSLiteralExpression && value.text == "true"
    }

    /**
     * The text i18next shows while the key is missing: a string second argument —
     * `t('key', 'Default text')` — or the `defaultValue` option. Quoted literals only, null otherwise.
     * vue-i18n's legacy `$t('key', 'fr')` takes a locale second, not a text: not read as one.
     */
    fun defaultValue(element: PsiElement): String? {
        val call = callOf(element) ?: return null
        val second = call.arguments.getOrNull(1)
        val legacyVue = call.methodExpression?.text?.substringAfterLast('.')?.startsWith('$') == true
        if (second is JSLiteralExpression && second.isQuotedLiteral && !legacyVue) return second.stringValue
        val option = optionsOf(element)?.findProperty("defaultValue")?.value
        return if (option is JSLiteralExpression && option.isQuotedLiteral) option.stringValue else null
    }

    /**
     * The other keys of `t(['specific', 'generic'])` when [element] is one of them: i18next shows the
     * first that exists. Empty when [element] is not a quoted literal of an array passed first.
     */
    fun fallbackKeys(element: PsiElement): List<JSLiteralExpression> {
        val literal = element as? JSLiteralExpression ?: element.parent as? JSLiteralExpression ?: return emptyList()
        val array = literal.parent as? JSArrayLiteralExpression ?: return emptyList()
        if (callOf(array)?.arguments?.firstOrNull() !== array) return emptyList()
        return array.expressions.filterIsInstance<JSLiteralExpression>().filter { it !== literal && it.isQuotedLiteral }
    }

    /** The call [element] is passed to, or sits inside an argument of. */
    private fun callOf(element: PsiElement): JSCallExpression? =
        PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java)

    /**
     * The options object [element]'s call passes: second — `t('key', { … })` — or third, after a
     * default value — `t('key', 'Default', { … })`.
     */
    private fun optionsOf(element: PsiElement): JSObjectLiteralExpression? {
        val arguments = callOf(element)?.arguments ?: return null
        return arguments.getOrNull(1) as? JSObjectLiteralExpression ?: arguments.getOrNull(2) as? JSObjectLiteralExpression
    }
}
