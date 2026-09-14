package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSDestructuringElement
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Extracts an i18n key from a string literal passed to a `t` obtained from a translation hook.
 *
 * Two hooks are recognised, and they give the hook's first argument different meanings:
 *  - react-i18next `useTranslation('ns' | ['ns1', 'ns2'], { keyPrefix })`: the argument names the
 *    namespaces, and the `keyPrefix` option is prepended to every key;
 *  - next-intl `useTranslations('Home')`: messages live in one file per locale, and the argument
 *    is the path of the object holding the keys — a key prefix, not a namespace.
 */
class ReactUseTranslationHookExtractor: KeyExtractor {

    private companion object {
        const val USE_TRANSLATION = "useTranslation"
        const val USE_TRANSLATIONS = "useTranslations"
        val HOOKS = setOf(USE_TRANSLATION, USE_TRANSLATIONS)
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
        val isNextIntl = hook?.methodExpression?.text == USE_TRANSLATIONS
        val hookArguments = hook?.arguments.orEmpty()
        val firstArgumentStrings = hookArguments.firstOrNull()?.let(::stringsOf).orEmpty()

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

    /** The `keyPrefix` of react-i18next's options object, when it is a plain string. */
    private fun keyPrefixOption(options: PsiElement?): String? {
        val literal = (options as? JSObjectLiteralExpression)?.findProperty("keyPrefix")?.value as? JSLiteralExpression
        return literal?.takeIf { it.isQuotedLiteral }?.stringValue
    }

    private fun resolveTranslationFunctionDefinition(element: PsiElement): PsiElement? {
        return PsiTreeUtil
            .getChildOfType(
                PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java),
                JSReferenceExpression::class.java
            )
            ?.reference
            ?.resolve()
    }

    private fun resolveDestructuringElement(t: PsiElement?): JSDestructuringElement? {
        return PsiTreeUtil.getParentOfType(t, JSDestructuringElement::class.java)
    }

    private fun resolveHook(literal: PsiElement): JSCallExpression? {
        // Primary: follow reference to local definition. react-i18next destructures the hook's
        // result (`const { t } = useTranslation()`); next-intl returns `t` itself
        // (`const t = useTranslations('Home')`), a plain variable initializer.
        val definition = resolveTranslationFunctionDefinition(literal)
        val viaRef = definition
            ?.let { resolveDestructuringElement(it) }
            ?.let { it.initializer as? JSCallExpression }
            ?: (definition as? JSVariable)?.initializer as? JSCallExpression
        if (viaRef != null && viaRef.methodExpression?.text in HOOKS) return viaRef

        // Fallback: scope walk when reference resolution goes to type declarations
        return resolveHookViaScopeWalk(literal)
    }

    private fun resolveHookViaScopeWalk(literal: PsiElement): JSCallExpression? {
        val tCall = PsiTreeUtil.getParentOfType(literal, JSCallExpression::class.java) ?: return null
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
            .firstOrNull { variable ->
                variable.name == fnName &&
                    (variable.initializer as? JSCallExpression)?.methodExpression?.text == USE_TRANSLATIONS
            }
            ?.initializer as? JSCallExpression
    }
}
