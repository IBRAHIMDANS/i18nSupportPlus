package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.extensions.lang.js.extractors.*
import com.ibrahimdans.i18n.plugin.factory.FoldingProvider
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.type
import com.intellij.lang.javascript.patterns.JSPatterns
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSThisExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

open class JsLang : Lang {

    override fun canExtractKey(element: PsiElement, translationFunctionNames: List<String>): Boolean {
        return translationFunctionNames.any { t ->
            JSPatterns.jsArgument(t, 0).let { pattern ->
                pattern.accepts(element) ||
                    (!isNestedInsideTemplateExpression(element) &&
                        !isInsideConditionalCondition(element) &&
                        pattern.accepts(PsiTreeUtil.findFirstParent(element) { it.parent?.type() == "JS:ARGUMENT_LIST" }))
            }
        } && isDirectOrConfiguredCall(element, translationFunctionNames)
          && extractRawKey(element) != null
    }

    // Returns false when the call has a non-`this` qualifier that is not in translationFunctionNames.
    // Guards against false positives like toast.t('key') when only "t" is configured.
    // this.$t is always accepted; i18n._ is accepted only if "i18n._" is a configured name.
    protected fun isDirectOrConfiguredCall(element: PsiElement, translationFunctionNames: List<String>): Boolean {
        val callExpr = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java) ?: return true
        val refExpr = callExpr.methodExpression as? JSReferenceExpression ?: return true
        val qualifier = refExpr.qualifier ?: return true
        if (qualifier is JSThisExpression) return true
        return refExpr.text in translationFunctionNames
    }

    private fun isNestedInsideTemplateExpression(element: PsiElement): Boolean {
        var current = element.parent
        while (current != null && current.type() != "JS:ARGUMENT_LIST") {
            if (current.type() == "JS:STRING_TEMPLATE_EXPRESSION") return true
            current = current.parent
        }
        return false
    }

    /**
     * Returns true if [element] is inside the condition part of a ternary expression
     * (i.e. before the '?'), not in a value branch.
     * Prevents false positives like 'remove' in: t(x === 'remove' ? 'key1' : 'key2')
     */
    private fun isInsideConditionalCondition(element: PsiElement): Boolean {
        var current = element.parent
        while (current != null && current.type() != "JS:ARGUMENT_LIST") {
            if (current.type() == "JS:CONDITIONAL_EXPRESSION") {
                val conditionNode = current.children.firstOrNull() ?: return false
                if (PsiTreeUtil.isAncestor(conditionNode, element, false)) return true
            }
            current = current.parent
        }
        return false
    }

    override fun extractRawKey(element: PsiElement): RawKey? {
        return listOf(
                    ReactUseTranslationHookExtractor(),
                    TemplateKeyExtractor(),
                    LiteralKeyExtractor(),
                    StringLiteralKeyExtractor(),
                    XmlAttributeKeyExtractor()
            ).find {it.canExtract(element)}?.extract(element)
        }

    override fun foldingProvider(): FoldingProvider = JsFoldingProvider()

    override fun translationExtractor(): TranslationExtractor = JsTranslationExtractor()

    override fun resolveLiteral(entry: PsiElement): PsiElement? {
        val typeName = entry.node.elementType.toString()
        return if (setOf("JS:STRING_LITERAL", "JS:STRING_TEMPLATE_EXPRESSION").contains (typeName)) entry
            else if (typeName == "JS:STRING_TEMPLATE_PART") entry.parent
            else null
    }
}