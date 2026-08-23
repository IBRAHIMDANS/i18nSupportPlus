package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.lang.js.extractors.LiteralKeyExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.ReactUseTranslationHookExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.TemplateKeyExtractor
import com.ibrahimdans.i18n.plugin.factory.ReferenceAssistant
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.parser.KeyParserBuilder
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.javascript.patterns.JSPatterns
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.ecma6.JSComputedPropertyNameOwner
import com.intellij.lang.javascript.psi.JSConditionalExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSThisExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnumField
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.ElementPatternCondition
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
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

    private fun isDirectOrConfiguredCall(element: PsiElement): Boolean {
        val callExpr = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java) ?: return true
        val refExpr = callExpr.methodExpression as? JSReferenceExpression ?: return true
        val qualifier = refExpr.qualifier ?: return true
        if (qualifier is JSThisExpression) return true
        return refExpr.text in translationFunctionNames
    }

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
                return ternaryArg.accepts(parent)
            }

            override fun accepts(o: Any?): Boolean {
                return JSPatterns.jsLiteralExpression().accepts(o) && isAlias(o as JSLiteralExpression)
                    || (v.accepts(o) && (o !is PsiElement || isDirectOrConfiguredCall(o)))
                    || (o is PsiElement && isLiteralInTernaryArg(o))
            }

            override fun accepts(o: Any?, context: ProcessingContext?): Boolean {
                return JSPatterns.jsLiteralExpression().accepts(o) && isAlias(o as JSLiteralExpression)
                    || (v.accepts(o, context) && (o !is PsiElement || isDirectOrConfiguredCall(o)))
                    || (o is PsiElement && isLiteralInTernaryArg(o))
            }

            override fun getCondition(): ElementPatternCondition<PsiElement>? {
                return v.condition as? ElementPatternCondition<PsiElement>
            }
        }


    override fun extractKey(element: PsiElement): FullKey? {
        val config = Settings.getInstance(element.project).config()
        val flatKeys = config.usesFlatKeys()
        val parser = (
            if (flatKeys) KeyParserBuilder.withoutTokenizer()
            else KeyParserBuilder.withSeparators(config.nsSeparator, config.keySeparator).withTemplateNormalizer()
        ).build()
        return listOf(
                ReactUseTranslationHookExtractor(),
                TemplateKeyExtractor(),
                LiteralKeyExtractor()
        )
            .find {it.canExtract(element)}
            ?.let {parser.parse(it.extract(element), emptyNamespace = flatKeys, firstComponentNamespace = config.firstComponentNs)}
    }
}