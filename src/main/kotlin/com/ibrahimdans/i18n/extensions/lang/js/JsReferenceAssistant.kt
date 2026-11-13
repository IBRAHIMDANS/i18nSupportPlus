package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.lang.js.extractors.DefineMessagesExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.LiteralKeyExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.NextIntlMethodExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.ReactIntlExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.ReactUseTranslationHookExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.SvelteI18nExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.TemplateKeyExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.TranslocoExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.TranslocoReadExtractor
import com.ibrahimdans.i18n.plugin.factory.ReferenceAssistant
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.rules.RuleDecision
import com.ibrahimdans.i18n.plugin.utils.ancestors
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.javascript.patterns.JSPatterns
import com.intellij.lang.javascript.psi.ecma6.JSComputedPropertyNameOwner
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSConditionalExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnumField
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.ElementPatternCondition
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext

internal class JsReferenceAssistant: ReferenceAssistant {

    companion object {
        private val translationFunctionNames by lazy {
            Extensions.TECHNOLOGY.extensionList
                .flatMap { it.translationFunctionNames() }
                .toSet()
        }

        /**
         * The method names a call is matched by.
         *
         * A configured name can be qualified — `i18n._`, `i18n.t` — while
         * [JSPatterns.jsArgument] matches the method name alone, so the qualifier is dropped
         * here and checked separately by [isDirectOrConfiguredCall]: that is what keeps
         * `toast.t('…')` out while letting `i18n.t('…')` through.
         *
         * This used to be the literals `t` and `$t`. Every other name a `Technology` publishes
         * — `$tc`, `$te`, `msg`, `i18n._`, `instant`, `_`, `$_`, `formatMessage` — was
         * annotated, since annotation reads the full list, but carried no reference: no
         * Ctrl+click, no rename, no find-usages on keys the README announces as supported.
         */
        private val callMethodNames by lazy {
            translationFunctionNames
                .map { it.substringAfterLast('.') }
                .filter { it.isNotBlank() }
                .toSet()
        }

        /**
         * react-intl's descriptor `id`s — `formatMessage({ id })`, `defineMessages` / `defineMessage`.
         * Annotated, but matched by no call pattern below: the key is a property of an object
         * argument, not the argument itself, so they carried no reference.
         */
        private val REACT_INTL_EXTRACTORS = listOf(ReactIntlExtractor(), DefineMessagesExtractor())

        /** next-intl's `t.rich('key')` and the other methods of `t`: named after the method, so matched by no call pattern. */
        private val NEXT_INTL_METHODS = listOf(NextIntlMethodExtractor())

        /** svelte-i18n's `$_({ id: 'key' })`: the key is a property of the argument, as react-intl's. */
        private val SVELTE_I18N = listOf(SvelteI18nExtractor())

        /**
         * Transloco's service and standalone calls (`translate('key')`), matched by no published name, and
         * `t('key')` under a `read` or `prefix`, whose key the generic extraction reads without it.
         */
        private val TRANSLOCO = listOf(TranslocoReadExtractor(), TranslocoExtractor())
    }

    private fun isDirectOrConfiguredCall(element: PsiElement): Boolean =
        isDirectOrConfiguredCall(element, translationFunctionNames)

    override fun pattern(): ElementPattern<out PsiElement> =
        object : ElementPattern<PsiElement> {
            private val v = JSPatterns.jsLiteralExpression()
                .andOr(*callMethodNames.map { JSPatterns.jsArgument(it, 0) }.toTypedArray())

            // Pattern matching a JSConditionalExpression passed as first argument of a call
            private val ternaryArg = JSPatterns.jsExpression()
                .andOr(*callMethodNames.map { JSPatterns.jsArgument(it, 0) }.toTypedArray())

            /**
             * Only a reference is resolved in a computed name, `[Keys.Save]`: a string key, `['save']`, may be
             * [element] itself, and asking it for its reference re-entered this pattern until the stack overflowed.
             */
            private fun isAlias(element: JSLiteralExpression): Boolean {
                val config = Settings.getInstance(element.project).config()
                return element.ancestors(false).toList()
                    .mapNotNull {(it as? JSProperty)?.name ?: (it as? JSComputedPropertyNameOwner)?.computedPropertyName?.let { (it.expression as? JSReferenceExpression)?.resolve() as? TypeScriptEnumField }?.name}
                    .reversed().joinToString(config.keySeparator).endsWith(element.text.unQuote())
            }

            // Checks if element is a string literal inside a ternary expression passed as first arg of t()/$t()
            private fun isLiteralInTernaryArg(element: PsiElement): Boolean {
                if (element !is JSLiteralExpression) return false
                val parent = element.parent as? JSConditionalExpression ?: return false
                return ternaryArg.accepts(parent) && jsRuleDecision(element) != RuleDecision.EXCLUDE
            }

            // A first argument of a call whose name no framework publishes but a rule includes.
            private fun isRuleIncludedArgument(element: PsiElement): Boolean {
                if (element !is JSLiteralExpression) return false
                val arguments = element.parent as? JSArgumentList ?: return false
                return arguments.arguments.firstOrNull() === element && jsRuleDecision(element) == RuleDecision.INCLUDE
            }

            /**
             * A key of `t(['specific', 'generic'])`: i18next shows the first one that exists, so each is a
             * key, matched by the same calls as a key passed directly — a template too, whose dynamic head
             * reaches its keys as `t(`errors.${code}`)` does. Without it, `errors.*` in
             * `t([`errors.${code}`, 'errors.unknown'])` had no usage and was reported unused.
             */
            private fun isKeyOfArrayArgument(element: PsiElement): Boolean {
                val isKey = element is JSStringTemplateExpression || (element is JSLiteralExpression && element.isQuotedLiteral)
                if (!isKey) return false
                val array = element.parent as? JSArrayLiteralExpression ?: return false
                val isFirstArgument = (array.parent as? JSArgumentList)?.arguments?.firstOrNull() === array
                if (!isFirstArgument) return false
                val matched = ternaryArg.accepts(array) || jsRuleDecision(element) == RuleDecision.INCLUDE
                return matched && isDirectOrConfiguredCall(element)
            }

            override fun accepts(o: Any?): Boolean {
                return JSPatterns.jsLiteralExpression().accepts(o) && isAlias(o as JSLiteralExpression)
                    || (v.accepts(o) && (o !is PsiElement || isDirectOrConfiguredCall(o)))
                    || (o is PsiElement && (isLiteralInTernaryArg(o) || isKeyOfArrayArgument(o) || isRuleIncludedArgument(o) || isReactIntlId(o) || isNextIntlMethodKey(o) || isSvelteI18nKey(o) || isTranslocoKey(o) || isConstantKey(o)))
            }

            override fun accepts(o: Any?, context: ProcessingContext?): Boolean {
                return JSPatterns.jsLiteralExpression().accepts(o) && isAlias(o as JSLiteralExpression)
                    || (v.accepts(o, context) && (o !is PsiElement || isDirectOrConfiguredCall(o)))
                    || (o is PsiElement && (isLiteralInTernaryArg(o) || isKeyOfArrayArgument(o) || isRuleIncludedArgument(o) || isReactIntlId(o) || isNextIntlMethodKey(o) || isSvelteI18nKey(o) || isTranslocoKey(o) || isConstantKey(o)))
            }

            override fun getCondition(): ElementPatternCondition<PsiElement>? {
                return v.condition as? ElementPatternCondition<PsiElement>
            }
        }


    private fun isReactIntlId(element: PsiElement): Boolean = activeExtractorOf(REACT_INTL_EXTRACTORS, element) != null

    private fun isNextIntlMethodKey(element: PsiElement): Boolean = activeExtractorOf(NEXT_INTL_METHODS, element) != null

    private fun isSvelteI18nKey(element: PsiElement): Boolean = activeExtractorOf(SVELTE_I18N, element) != null

    private fun isTranslocoKey(element: PsiElement): Boolean = activeExtractorOf(TRANSLOCO, element) != null

    /**
     * The literal of `const SAVE_KEY = 'common:save'`, `{ save: '…' } as const` or `enum { Save = '…' }`
     * once a translation call is passed the constant: *Find Usages* finds it, Shift+F6 rewrites it once.
     * The call itself holds no reference — Shift+F6 there would rewrite `KEYS.save`.
     */
    private fun isConstantKey(element: PsiElement): Boolean = ConstantKeyResolver.declarationArgument(element) != null

    /** Parsed like the annotator parses it — a module's key template included — so both agree on the key. */
    override fun extractKey(element: PsiElement): FullKey? {
        // Read at the call passing the constant, under its hook's namespace.
        ConstantKeyResolver.declarationKey(element)?.let { return RawKeyParser(element.project).parse(it, element) }
        val extractor = activeExtractorOf(REACT_INTL_EXTRACTORS + NEXT_INTL_METHODS + SVELTE_I18N + TRANSLOCO, element)
            ?: listOf(
                ReactUseTranslationHookExtractor(),
                TemplateKeyExtractor(),
                LiteralKeyExtractor()
            ).find { it.canExtract(element) }
        return extractor?.let { RawKeyParser(element.project).parse(it.extract(element), element) }
    }
}