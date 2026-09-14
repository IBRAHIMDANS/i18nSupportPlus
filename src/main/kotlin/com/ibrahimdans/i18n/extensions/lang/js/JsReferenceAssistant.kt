package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.lang.js.extractors.LiteralKeyExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.ReactUseTranslationHookExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.TemplateKeyExtractor
import com.ibrahimdans.i18n.plugin.factory.ReferenceAssistant
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.rules.RuleDecision
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.javascript.patterns.JSPatterns
import com.intellij.lang.javascript.psi.ecma6.JSComputedPropertyNameOwner
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSConditionalExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnumField
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.ElementPatternCondition
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parents
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

            private fun isAlias(element: JSLiteralExpression): Boolean {
                val config = Settings.getInstance(element.project).config()
                return element.parents(false).toList()
                    .mapNotNull {(it as? JSProperty)?.name ?: (it as? JSComputedPropertyNameOwner)?.computedPropertyName?.let {it.expression?.reference?.resolve() as? TypeScriptEnumField }?.name}
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

            override fun accepts(o: Any?): Boolean {
                return JSPatterns.jsLiteralExpression().accepts(o) && isAlias(o as JSLiteralExpression)
                    || (v.accepts(o) && (o !is PsiElement || isDirectOrConfiguredCall(o)))
                    || (o is PsiElement && (isLiteralInTernaryArg(o) || isRuleIncludedArgument(o)))
            }

            override fun accepts(o: Any?, context: ProcessingContext?): Boolean {
                return JSPatterns.jsLiteralExpression().accepts(o) && isAlias(o as JSLiteralExpression)
                    || (v.accepts(o, context) && (o !is PsiElement || isDirectOrConfiguredCall(o)))
                    || (o is PsiElement && (isLiteralInTernaryArg(o) || isRuleIncludedArgument(o)))
            }

            override fun getCondition(): ElementPatternCondition<PsiElement>? {
                return v.condition as? ElementPatternCondition<PsiElement>
            }
        }


    /** Parsed like the annotator parses it — a module's key template included — so both agree on the key. */
    override fun extractKey(element: PsiElement): FullKey? =
        listOf(
                ReactUseTranslationHookExtractor(),
                TemplateKeyExtractor(),
                LiteralKeyExtractor()
        )
            .find {it.canExtract(element)}
            ?.let { RawKeyParser(element.project).parse(it.extract(element), element) }
}