package com.ibrahimdans.i18n.extract.i18next

import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.utils.unQuote

// What the extractors read of tree-sitter's TSX tree. CodeNode has no field names, so a node's
// parts are found by position and type, the way the grammar lays them out.

internal const val CALL_EXPRESSION = "call_expression"
internal const val ARGUMENTS = "arguments"
internal const val STRING = "string"
internal const val TEMPLATE_STRING = "template_string"
internal const val TEMPLATE_SUBSTITUTION = "template_substitution"
internal const val TERNARY_EXPRESSION = "ternary_expression"
internal const val JSX_ATTRIBUTE = "jsx_attribute"

private const val ARRAY = "array"
private const val OBJECT = "object"
private const val PAIR = "pair"
private const val VARIABLE_DECLARATOR = "variable_declarator"
private const val OBJECT_PATTERN = "object_pattern"
private const val SHORTHAND_PATTERN = "shorthand_property_identifier_pattern"
private const val IDENTIFIER = "identifier"
private const val USE_TRANSLATION = "useTranslation"

/** The tokens separating the items of an argument list, an array or an object, and comments. */
private val SEPARATORS = setOf("(", ")", "[", "]", "{", "}", ",", "comment")

/** The nodes a `const` declared in them is local to: what the hook binding `t` is looked up in. */
private val SCOPES = setOf(
    "program", "arrow_function", "function_declaration", "function_expression", "method_definition",
    "generator_function_declaration", "generator_function",
)

/**
 * A string literal. A TypeScript type `string` is a `string` node too, an unquoted one, which is
 * how `t(x as string)` would otherwise hold a key.
 */
internal fun CodeNode.isQuotedString(): Boolean = type == STRING && text.firstOrNull().let { it == '\'' || it == '"' }

/** The text of a string literal without its quotes, as written: escapes are kept, as the PSI extractors keep them. */
internal fun CodeNode.unquoted(): String = text.unQuote()

/** The items of an argument list, an array or an object, without their separators. */
internal fun CodeNode.items(): List<CodeNode> = children.filter { it.type !in SEPARATORS }

/** `t` of `t('key')`, `i18n.t` of `i18n.t('key')`: the callee as written. */
internal fun CodeNode.calleeText(): String? = children.firstOrNull()?.text

/** The arguments of a call, separators left out. */
internal fun CodeNode.arguments(): List<CodeNode> = children.lastOrNull { it.type == ARGUMENTS }?.items().orEmpty()

/**
 * The call to one of [names] whose first argument holds [node], null when there is none.
 *
 * [node] may sit deeper than the argument itself, `t(cond ? 'a' : 'b')`, as the plugin's
 * `JsLang` accepts — but not inside a template literal of the argument, nor in a ternary's condition.
 */
internal fun translationCallOf(node: CodeNode, names: Set<String>): CodeNode? {
    var child = node
    var current = node.parent
    while (current != null && current.type != ARGUMENTS) {
        if (current.type == TEMPLATE_STRING) return null
        if (current.type == TERNARY_EXPRESSION && current.children.firstOrNull() == child) return null
        child = current
        current = current.parent
    }
    if (current == null || current.items().firstOrNull() != child) return null
    return current.parent?.takeIf { it.type == CALL_EXPRESSION && it.calleeText() in names }
}

/** A string literal's text, or the string literals of an array: `'ns'` → [ns], `['a', 'b']` → [a, b]. */
internal fun stringsOf(node: CodeNode?): List<String> = when {
    node == null -> emptyList()
    node.isQuotedString() -> listOf(node.unquoted())
    node.type == ARRAY -> node.items().filter { it.isQuotedString() }.map { it.unquoted() }
    else -> emptyList()
}

/** The value of property [name] of an object literal, null when [node] is no object or has no such property. */
internal fun propertyOf(node: CodeNode?, name: String): CodeNode? {
    if (node?.type != OBJECT) return null
    val pair = node.items().firstOrNull { it.type == PAIR && it.children.firstOrNull()?.let { key -> key.text == name || key.unquoted() == name } == true }
    return pair?.children?.lastOrNull()
}

/** The namespaces of [call]'s `{ ns }` option: `t(key, { ns: 'a' })`, `t(key, { ns: ['a', 'b'] })`. */
internal fun namespacesOption(call: CodeNode): List<String> = stringsOf(propertyOf(call.arguments().getOrNull(1), "ns"))

/**
 * The `useTranslation(…)` call whose result binds [call]'s function, `const { t } = useTranslation()`,
 * in the nearest enclosing scope that declares one — what the plugin's
 * `ReactUseTranslationHookExtractor` finds by resolving `t`. Null for a qualified call, `i18n.t`.
 */
internal fun hookOf(call: CodeNode): CodeNode? {
    val callee = call.children.firstOrNull()?.takeIf { it.type == IDENTIFIER } ?: return null
    return generateSequence(call.parent) { it.parent }
        .filter { it.type in SCOPES }
        .firstNotNullOfOrNull { scope -> declaratorsOf(scope).firstNotNullOfOrNull { hookBinding(it, callee.text) } }
}

/** The variable declarators of [scope], not those of the functions nested in it. */
private fun declaratorsOf(scope: CodeNode): Sequence<CodeNode> = sequence {
    val pending = ArrayDeque(scope.children)
    while (pending.isNotEmpty()) {
        val node = pending.removeFirst()
        if (node.type == VARIABLE_DECLARATOR) yield(node)
        if (node.type !in SCOPES) pending.addAll(node.children)
    }
}

/** The hook call of `const { t } = useTranslation(…)` when [declarator] is one binding [name]. */
private fun hookBinding(declarator: CodeNode, name: String): CodeNode? {
    val parts = declarator.children
    val pattern = parts.firstOrNull()?.takeIf { it.type == OBJECT_PATTERN } ?: return null
    if (pattern.children.none { it.type == SHORTHAND_PATTERN && it.text == name }) return null
    return parts.lastOrNull()?.takeIf { it.type == CALL_EXPRESSION && it.calleeText() == USE_TRANSLATION }
}
