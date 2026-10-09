package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.utils.type
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.Language
import com.intellij.lang.javascript.patterns.JSPatterns
import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.psi.JSBinaryExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSThisExpression
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Extracts a string of JavaScript: a literal, a template literal — `` `${count} files` `` — or a
 * concatenation — `'Hello ' + user.name + '!'` — whose expressions become the variables of the
 * message: `{{count}} files`, `Hello {{name}}!`.
 */
internal class JsTranslationExtractor: TranslationExtractor {
    override fun canExtract(element: PsiElement): Boolean {
        val jsLang = Language.findLanguageByID("JavaScript") ?: return false
        if (!element.containingFile.language.isKindOf(jsLang)) return false
        return "JS:STRING_LITERAL" == element.type() ||
            (element.node?.elementType == JSTokenTypes.STRING_TEMPLATE_PART && element.parent is JSStringTemplateExpression)
    }
    override fun isExtracted(element: PsiElement): Boolean {
        if (!JSPatterns.jsArgument("t", 0).accepts(element.parent)) return false
        return isDirectOrConfiguredCall(element)
    }
    override fun text(element: PsiElement): String {
        val parts = parts(element) ?: return element.text.unQuote()
        val placeholders = variables(element).associate { it.expression to it.placeholder }
        return parts.joinToString("") { part ->
            when (part) {
                is Part.Text -> part.text
                is Part.Value -> placeholders.getValue(part.expression.text)
            }
        }
    }

    override fun textRange(element: PsiElement): TextRange = message(element).textRange

    override fun variables(element: PsiElement): List<MessageVariable> {
        val expressions = parts(element)?.filterIsInstance<Part.Value>()?.map { it.expression } ?: return emptyList()
        return MessageVariables.of(expressions, MessageVariables.placeholderSyntax(element))
    }

    override fun template(element: PsiElement): (argument: String) -> String {
        val call = call(element)
        val variables = variables(element)
        return { call(it, variables) }
    }

    override fun call(element: PsiElement): (argument: String, variables: List<MessageVariable>) -> String {
        val function = translationFunction(element)
        return { argument, variables -> "$function($argument${MessageVariables.options(variables)})" }
    }

    /** The template literal or the concatenation [element] belongs to, else its own literal. */
    private fun message(element: PsiElement): PsiElement {
        (element.parent as? JSStringTemplateExpression)?.let { return it }
        val literal = element.parent
        var root: PsiElement = literal
        while (true) {
            val sum = root.parent as? JSBinaryExpression ?: break
            if (sum.operationSign != JSTokenTypes.PLUS) break
            root = sum
        }
        // `count + 1 + ' items'` adds before it concatenates: a string must come first or second.
        if (root is JSBinaryExpression && operands(root).take(2).none { it.isString() }) return literal
        return root
    }

    /** A piece of a message: text as written, or a value the code computes. */
    private sealed interface Part {
        data class Text(val text: String) : Part
        data class Value(val expression: JSExpression) : Part
    }

    /** The pieces of the message [element] belongs to, in order; null for a plain literal. */
    private fun parts(element: PsiElement): List<Part>? = when (val message = message(element)) {
        is JSStringTemplateExpression -> generateSequence(message.firstChild) { it.nextSibling }
            .mapNotNull { child ->
                when {
                    child is JSExpression -> Part.Value(child)
                    child.node?.elementType == JSTokenTypes.STRING_TEMPLATE_PART -> Part.Text(child.text)
                    else -> null
                }
            }
            .toList()
        is JSBinaryExpression -> operands(message).map { operand ->
            if (operand.isString()) Part.Text((operand as JSLiteralExpression).stringValue.orEmpty()) else Part.Value(operand)
        }
        else -> null
    }

    private fun operands(expression: JSExpression): List<JSExpression> =
        if (expression is JSBinaryExpression && expression.operationSign == JSTokenTypes.PLUS) {
            operands(expression.lOperand ?: return listOf(expression)) + operands(expression.rOperand ?: return listOf(expression))
        } else {
            listOf(expression)
        }

    private fun JSExpression.isString(): Boolean = this is JSLiteralExpression && isQuotedLiteral

    override fun scopeNamespaces(element: PsiElement): List<String> =
        TranslationHookInScope.find(element)?.namespaces.orEmpty()

    private fun isDirectOrConfiguredCall(element: PsiElement): Boolean {
        val callExpr = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java) ?: return true
        val refExpr = callExpr.methodExpression as? JSReferenceExpression ?: return true
        val qualifier = refExpr.qualifier ?: return true
        if (qualifier is JSThisExpression) return true
        val fnNames = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
        return refExpr.text in fnNames
    }
}