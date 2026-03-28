package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.KeyElementType
import com.ibrahimdans.i18n.plugin.utils.type
import com.intellij.lang.javascript.patterns.JSPatterns
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.psi.PsiElement

/**
 * Extracts i18n key from JS string template expression
 */
class TemplateKeyExtractor : KeyExtractor {

    private val i18nArgumentPatterns = listOf(
        JSPatterns.jsArgument("t", 0),
        JSPatterns.jsArgument("\$t", 0),
        JSPatterns.jsArgument("i18n.t", 0),
    )

    private fun isTemplateExpression(element: PsiElement): Boolean = element.type() == "JS:STRING_TEMPLATE_EXPRESSION"

    /** Checks if the template literal is used as an i18n function argument, or has a namespace separator in its static parts */
    private fun isI18nContext(element: PsiElement): Boolean {
        if (i18nArgumentPatterns.any { it.accepts(element) }) return true
        // Fallback: check that static parts of the template contain ':' (namespace separator hint)
        val staticText = element.node.getChildren(null)
            .filter { it !is JSExpression }
            .joinToString("") { it.text }
        return staticText.contains(":")
    }

    override fun canExtract(element: PsiElement): Boolean =
        isTemplateExpression(element) && isI18nContext(element)

    override fun extract(element: PsiElement): RawKey =
        RawKey(
            element.node.getChildren(null).map {
                KeyElement(it.text, if (it is JSExpression) KeyElementType.TEMPLATE else KeyElementType.LITERAL)
            },
            OptionsExtractor.extractNamespaces(element)
        )
}