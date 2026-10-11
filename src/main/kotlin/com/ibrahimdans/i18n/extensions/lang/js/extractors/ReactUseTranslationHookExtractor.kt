package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSDestructuringElement
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSParenthesizedExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil

/**
 * Extracts an i18n key from a string literal passed to a `t` obtained from a translation hook.
 *
 * Two families are recognised, and they give the first argument different meanings:
 *  - react-i18next `useTranslation('ns' | ['ns1', 'ns2'], { keyPrefix })`: the argument names the
 *    namespaces, and the `keyPrefix` option is prepended to every key;
 *  - next-intl `useTranslations('Home')`, and its Server Component twin
 *    `await getTranslations('Home' | { namespace: 'Home' })`: messages live in one file per locale,
 *    and the argument is the path of the object holding the keys — a key prefix, not a namespace.
 */
class ReactUseTranslationHookExtractor: KeyExtractor {

    private companion object {
        const val USE_TRANSLATION = "useTranslation"
        const val USE_TRANSLATIONS = "useTranslations"
        const val GET_TRANSLATIONS = "getTranslations"
        val NEXT_INTL_HOOKS = setOf(USE_TRANSLATIONS, GET_TRANSLATIONS)
        val HOOKS = NEXT_INTL_HOOKS + USE_TRANSLATION
        const val AWAIT = "await"
    }

    override fun canExtract(element: PsiElement): Boolean {
        // Accept both JSLiteralExpression and its direct leaf token (returned by findElementAt).
        val literal = element as? JSLiteralExpression
            ?: element.parent as? JSLiteralExpression
            ?: return false
        if (!literal.isQuotedLiteral) return false
        return resolveHook(element)?.methodExpression?.text in HOOKS
    }

    override fun extract(element: PsiElement): RawKey {
        val hook = resolveHook(element)
        val isNextIntl = hook?.methodExpression?.text in NEXT_INTL_HOOKS
        val hookArguments = hook?.arguments.orEmpty()
        val firstArgumentStrings = hookArguments.firstOrNull()?.let {
            if (isNextIntl) listOfNotNull(nextIntlNamespace(it)) else stringsOf(it)
        }.orEmpty()

        val hookNamespaces = if (isNextIntl) emptyList() else firstArgumentStrings
        val prefix = if (isNextIntl) firstArgumentStrings.firstOrNull() else keyPrefixOption(hookArguments.getOrNull(1))

        // i18next: an explicit `t(key, { ns })` option overrides the hook default namespace.
        val optionsNamespaces = OptionsExtractor.extractNamespaces(element)
        return RawKey(
            listOf(KeyElement.literal(element.text.unQuote())),
            optionsNamespaces.ifEmpty { hookNamespaces },
            prefix?.takeIf { it.isNotBlank() }?.let { listOf(KeyElement.literal(it)) }.orEmpty()
        )
    }

    /** The quoted strings a hook argument carries: a single literal, or an array of them. */
    private fun stringsOf(argument: PsiElement): List<String> = when (argument) {
        is JSLiteralExpression ->
            if (argument.isQuotedLiteral) listOfNotNull(argument.stringValue) else emptyList()
        is JSArrayLiteralExpression ->
            argument.expressions
                .filterIsInstance<JSLiteralExpression>()
                .filter { it.isQuotedLiteral }
                .mapNotNull { it.stringValue }
        else -> emptyList()
    }

    /**
     * next-intl's namespace: `useTranslations('Home')` / `getTranslations('Home')`, or the
     * `namespace` of `getTranslations({ locale, namespace: 'Home' })`.
     */
    private fun nextIntlNamespace(argument: PsiElement): String? {
        val literal = (argument as? JSObjectLiteralExpression)?.findProperty("namespace")?.value ?: argument
        return (literal as? JSLiteralExpression)?.takeIf { it.isQuotedLiteral }?.stringValue
    }

    /**
     * The call behind a variable initializer, seen through `await` and parentheses: next-intl's
     * `const t = await getTranslations('Home')` initializes `t` from an await expression.
     */
    private fun callOf(expression: PsiElement?): JSCallExpression? {
        if (expression == null) return null
        return when (expression) {
            is JSCallExpression -> expression
            is JSParenthesizedExpression -> callOf(expression.innerExpression)
            // `await` is the leading keyword of its expression, whatever PSI class the platform
            // version gives it; the awaited operand is its only expression child.
            else -> if (expression.firstChild?.text == AWAIT) {
                callOf(PsiTreeUtil.getChildOfType(expression, JSExpression::class.java))
            } else null
        }
    }

    /** The `keyPrefix` of react-i18next's options object, when it is a plain string. */
    private fun keyPrefixOption(options: PsiElement?): String? {
        val literal = (options as? JSObjectLiteralExpression)?.findProperty("keyPrefix")?.value as? JSLiteralExpression
        return literal?.takeIf { it.isQuotedLiteral }?.stringValue
    }

    private fun resolveTranslationFunctionDefinition(call: JSCallExpression): PsiElement? =
        PsiTreeUtil.getChildOfType(call, JSReferenceExpression::class.java)?.reference?.resolve()

    private fun resolveDestructuringElement(t: PsiElement?): JSDestructuringElement? {
        return PsiTreeUtil.getParentOfType(t, JSDestructuringElement::class.java)
    }

    /**
     * The hook call that defined the function called with [literal], cached on that call until the
     * PSI changes: the annotator, inlay hints, gutter and references each ask it — the inlay
     * collector two or three times per key — and each time resolved `t` and walked the scope.
     */
    private fun resolveHook(literal: PsiElement): JSCallExpression? {
        val call = PsiTreeUtil.getParentOfType(literal, JSCallExpression::class.java) ?: return null
        return CachedValuesManager.getCachedValue(call) {
            CachedValueProvider.Result.create(computeHook(call), PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    private fun computeHook(call: JSCallExpression): JSCallExpression? {
        // Primary: follow reference to local definition. react-i18next destructures the hook's
        // result (`const { t } = useTranslation()`); next-intl returns `t` itself
        // (`const t = useTranslations('Home')`, `const t = await getTranslations('Home')`), a plain
        // variable initializer.
        val definition = resolveTranslationFunctionDefinition(call)
        val viaRef = definition
            ?.let { resolveDestructuringElement(it) }
            ?.let { it.initializer as? JSCallExpression }
            ?: callOf((definition as? JSVariable)?.initializer)
        if (viaRef != null && viaRef.methodExpression?.text in HOOKS) return viaRef

        // Fallback: scope walk when reference resolution goes to type declarations
        return resolveHookViaScopeWalk(call)
    }

    private fun resolveHookViaScopeWalk(tCall: JSCallExpression): JSCallExpression? {
        val fnName = PsiTreeUtil.getChildOfType(tCall, JSReferenceExpression::class.java)?.text ?: return null
        val scope = PsiTreeUtil.getParentOfType(tCall, JSFunction::class.java) ?: return null
        val namePattern = Regex("\\b${Regex.escape(fnName)}\\b")
        PsiTreeUtil.findChildrenOfType(scope, JSDestructuringElement::class.java)
            .firstOrNull { elem ->
                (elem.initializer as? JSCallExpression)?.methodExpression?.text == USE_TRANSLATION
                    && namePattern.containsMatchIn(elem.text)
            }
            ?.let { return it.initializer as? JSCallExpression }
        return PsiTreeUtil.findChildrenOfType(scope, JSVariable::class.java)
            .filter { it.name == fnName }
            .firstNotNullOfOrNull { variable ->
                callOf(variable.initializer)?.takeIf { it.methodExpression?.text in NEXT_INTL_HOOKS }
            }
    }
}
