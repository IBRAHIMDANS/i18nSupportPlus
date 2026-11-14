package com.ibrahimdans.i18n.extract.i18next

import com.ibrahimdans.i18n.plugin.parser.CodeKeyExtractor
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement

/**
 * The key of i18next's selector API, `t($ => $.button.save)`, on a tree-sitter TSX tree: the path the
 * arrow reads on its parameter, `button.save`.
 *
 * The plugin's `SelectorKeyExtractor`: the node read is the arrow's body, the member chain; the call's
 * hook and options apply as to a string key, and under a hook declaring several namespaces a first
 * segment naming one of the others routes the rest of the path to it (i18next's `keysFromSelector`).
 * The path is joined with `.`, i18next's default key separator.
 */
class SelectorCallKeyExtractor(private val functionNames: Set<String> = I18NEXT_FUNCTIONS) : CodeKeyExtractor {

    private val calls = TranslationCallKeyExtractor(functionNames)

    override fun canExtract(node: CodeNode): Boolean = selectorPathOf(node) != null && translationCallOf(node, functionNames) != null

    override fun extract(node: CodeNode): RawKey? {
        val path = selectorPathOf(node) ?: return null
        val call = translationCallOf(node, functionNames) ?: return null
        val base = calls.keyAt(call, path.joinToString(SEPARATOR))
        val routed = namespacesOption(call).isEmpty() && path.size > 1 && path.first() in base.arguments.drop(1)
        if (!routed) return base
        return base.copy(keyElements = listOf(KeyElement.literal(path.drop(1).joinToString(SEPARATOR))), arguments = listOf(path.first()))
    }

    private companion object {
        const val SEPARATOR = "."
    }
}

private const val ARROW_FUNCTION = "arrow_function"
private const val FORMAL_PARAMETERS = "formal_parameters"
private const val MEMBER_EXPRESSION = "member_expression"
private const val SUBSCRIPT_EXPRESSION = "subscript_expression"
private const val PROPERTY_IDENTIFIER = "property_identifier"
private const val IDENTIFIER = "identifier"

/**
 * True when [node] is a string read as a member inside a selector, `'not-found'` of
 * `$ => $.errors['not-found']`: a segment of the selector's key, never a key of its own.
 */
internal fun isSelectorSegment(node: CodeNode): Boolean {
    if (!node.isQuotedString()) return false
    var access = node.parent?.takeIf { it.type == SUBSCRIPT_EXPRESSION && it.children.getOrNull(2) == node } ?: return false
    while (true) {
        val parent = access.parent ?: return false
        if ((parent.type == MEMBER_EXPRESSION || parent.type == SUBSCRIPT_EXPRESSION) && parent.children.firstOrNull() == access) access = parent
        else return selectorPathOf(access) != null
    }
}

/** The members [node] reads on its arrow's single parameter, or null when [node] is no selector's body. */
private fun selectorPathOf(node: CodeNode): List<String>? {
    if (node.type != MEMBER_EXPRESSION && node.type != SUBSCRIPT_EXPRESSION) return null
    val arrow = node.parent?.takeIf { it.type == ARROW_FUNCTION && it.children.lastOrNull() == node } ?: return null
    val parameter = parameterOf(arrow) ?: return null
    val segments = ArrayDeque<String>()
    var current = node
    while (true) {
        val parts = current.children
        when (current.type) {
            MEMBER_EXPRESSION -> {
                val property = parts.lastOrNull()?.takeIf { it.type == PROPERTY_IDENTIFIER } ?: return null
                segments.addFirst(property.text)
                current = parts.first()
            }
            SUBSCRIPT_EXPRESSION -> {
                val index = parts.getOrNull(2)?.takeIf { it.isQuotedString() } ?: return null
                segments.addFirst(index.unquoted())
                current = parts.first()
            }
            IDENTIFIER -> return segments.toList().takeIf { current.text == parameter && it.isNotEmpty() }
            else -> return null
        }
    }
}

/** The name of [arrow]'s single parameter: `$` of `$ => …`, `($) => …` or `($: Selector) => …`. */
private fun parameterOf(arrow: CodeNode): String? {
    val first = arrow.children.firstOrNull() ?: return null
    if (first.type == IDENTIFIER) return first.text
    if (first.type != FORMAL_PARAMETERS) return null
    val parameter = first.items().singleOrNull() ?: return null
    val name = if (parameter.type == IDENTIFIER) parameter else parameter.children.firstOrNull()
    return name?.takeIf { it.type == IDENTIFIER }?.text
}

