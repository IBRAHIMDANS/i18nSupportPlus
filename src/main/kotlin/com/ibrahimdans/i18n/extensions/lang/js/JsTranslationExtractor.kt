package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.lang.js.extractors.ReactIntlExtractor
import com.ibrahimdans.i18n.plugin.factory.CallTemplate
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.rules.RuleDecision
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.ibrahimdans.i18n.plugin.utils.type
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.Language
import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSBinaryExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/**
 * Extracts a string of JavaScript: a literal, a template literal — `` `${count} files` `` — or a
 * concatenation — `'Hello ' + user.name + '!'` — whose expressions become the variables of the
 * message: `{{count}} files`, `Hello {{name}}!`.
 */
internal class JsTranslationExtractor: TranslationExtractor {
    override fun canExtract(element: PsiElement): Boolean {
        val jsLang = Language.findLanguageByID("JavaScript") ?: return false
        if (!element.containingFile.language.isKindOf(jsLang)) return false
        return isStringToken(element)
    }

    /** A string literal's token, or a piece of text of a template literal. */
    internal fun isStringToken(element: PsiElement): Boolean =
        "JS:STRING_LITERAL" == element.type() ||
            (element.node?.elementType == JSTokenTypes.STRING_TEMPLATE_PART && element.parent is JSStringTemplateExpression)

    override fun isExtracted(element: PsiElement): Boolean = ExtractedKey.holds(element)

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
        if (ParaglideCall.applies(element)) {
            val messages = ParaglideCall.objectName(element)
            return { argument, variables -> ParaglideCall.call(messages, argument.unQuote(), MessageVariables.inputs(variables)) }
        }
        if (ReactIntlCall.applies(element)) {
            val function = formatMessageInScope(element) ?: ReactIntlCall.DEFAULT_FORMAT_MESSAGE
            val defaultMessage = ReactIntlCall.defaultMessage(element, text(element), variables(element))
            return { argument, variables -> ReactIntlCall.call(function, argument, variables, defaultMessage(variables)) }
        }
        val function = translationFunction(element)
        return { argument, variables -> "$function($argument${MessageVariables.options(variables)})" }
    }

    /** react-intl: a string becomes `intl.formatMessage(…)`, which needs the `intl` of a `useIntl()`. */
    override fun refusal(element: PsiElement): String? =
        if (!ParaglideCall.applies(element) && ReactIntlCall.applies(element)) ReactIntlCall.refusal(element) else null

    /** Paraglide: the call just written needs the message object, imported once if the file lacks it. */
    override fun postProcess(editor: Editor, offset: Int) {
        val project = editor.project ?: return
        val documents = PsiDocumentManager.getInstance(project)
        documents.commitDocument(editor.document)
        val file = documents.getPsiFile(editor.document) ?: return
        val element = file.findElementAt(offset) ?: return
        if (ModulePresets.presetOf(element) == ParaglideCall.PRESET) ParaglideCall.addImport(file, editor.document)
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
}

/**
 * Whether a string is already the key of a translation call, so that *Extract I18n*, *Batch
 * Extract*, *Internationalize…* and the hard-coded text inspections leave it alone.
 *
 * Only `t('…')` used to count: a key in `$t('…')`, `i18n._('…')`, `formatMessage({ id })` or the
 * `translate('…')` of a module's call template was offered for extraction and, accepted, became a
 * translation of itself. The calls recognised are the ones the key pipeline reads — the names the
 * technologies publish, restricted to the module's preset as the annotator restricts them, with
 * the same qualifier and import rules ([isDirectOrConfiguredCall]) — the callee of the module's
 * call template, and the react-intl descriptor. A bare `t` is always one: every
 * extractor writes it under a translation hook, and vue-i18n's `useI18n()` gives it without
 * publishing it.
 *
 * The one copy [JsTranslationExtractor] and [JsxTranslationExtractor] share.
 */
internal object ExtractedKey {

    private const val BARE_T = "t"

    private val CALLEE = Regex("""[\p{L}\p{N}_$.]+$""")

    /** True when [element], a string token, is the first argument of a translation call or the `id` of a react-intl descriptor. */
    fun holds(element: PsiElement): Boolean {
        val preset = ModulePresets.presetOf(element)
        val literal = element.parent as? JSLiteralExpression
        if (literal != null && ModulePresets.allows(preset, ReactIntlCall.PRESET) && ReactIntlExtractor.isFormatMessageDescriptor(literal)) return true
        val call = (element.parent?.parent as? JSArgumentList)?.parent as? JSCallExpression ?: return false
        val callee = call.methodExpression as? JSReferenceExpression ?: return false
        // The module's own wrapper: no framework publishes it, an import gate on its name (Transloco's
        // `translate`) must not keep it out, and every argument it takes — the key, the text a
        // `{text}` template writes after it — is the extraction's own.
        if (callee.text == templateCallee(element)) return true
        if (call.arguments.firstOrNull() !== element.parent) return false
        val published = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
        val names = ModulePresets.restrict(published, preset) + BARE_T +
            // A call a rule includes is a translation call, as the annotator reads it.
            listOfNotNull(callee.text.takeIf { jsRuleDecision(element) == RuleDecision.INCLUDE })
        if (names.none { it.substringAfterLast('.') == callee.referenceName }) return false
        return isDirectOrConfiguredCall(element, names)
    }

    /**
     * The function the module's call template calls — `translate` for `translate({key}, {text})`,
     * `tr` for `tr({text}, {key})` — or null: no template, or one that names no function.
     */
    fun templateCallee(element: PsiElement): String? =
        CallTemplate.of(element)?.substringBefore(CallTemplate.KEY)?.substringBefore('(')?.trim()?.let { CALLEE.find(it)?.value }
}