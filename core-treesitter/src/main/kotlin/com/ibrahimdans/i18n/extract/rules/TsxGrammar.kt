package com.ibrahimdans.i18n.extract.rules

import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.rules.CodeGrammar
import com.ibrahimdans.i18n.plugin.utils.unQuote

/**
 * [CodeGrammar] over tree-sitter's TSX tree: the node types the grammar lays out, read by position.
 * The plugin's `PsiJsGrammar` answers the same questions off the JavaScript PSI.
 *
 * The imports of the file last read are kept until a node of another file comes: one scan per file,
 * not one per key.
 */
class TsxGrammar : CodeGrammar {

    @Volatile
    private var imports: Pair<CodeNode, Set<String>>? = null

    override fun isStringLiteral(node: CodeNode): Boolean =
        node.type == STRING && node.text.firstOrNull().let { it == '\'' || it == '"' }

    /** As written: escapes are kept, as the PSI extractors keep them. */
    override fun stringValue(node: CodeNode): String = node.text.unQuote()

    override fun argumentOf(node: CodeNode): CodeGrammar.CallArgument? {
        var child = node
        var current = node.parent
        while (current != null && current.type != ARGUMENTS) {
            when (current.type) {
                PARENTHESIZED_EXPRESSION -> {}
                TERNARY_EXPRESSION -> if (current.children.firstOrNull() == child) return null
                else -> return null
            }
            child = current
            current = current.parent
        }
        if (current == null) return null
        val index = current.items().indexOf(child).takeIf { it >= 0 } ?: return null
        val call = current.parent?.takeIf { it.type == CALL_EXPRESSION } ?: return null
        return CodeGrammar.CallArgument(call, index)
    }

    override fun arguments(call: CodeNode): List<CodeNode> = call.children.lastOrNull { it.type == ARGUMENTS }?.items().orEmpty()

    override fun callee(call: CodeNode): String? =
        call.children.firstOrNull()?.takeIf { it.type == IDENTIFIER || it.type == MEMBER_EXPRESSION }?.text

    override fun propertyOf(node: CodeNode): CodeGrammar.ObjectProperty? {
        val pair = node.parent?.takeIf { it.type == PAIR && it.children.lastOrNull() == node } ?: return null
        val owner = pair.parent?.takeIf { it.type == OBJECT } ?: return null
        val name = propertyName(pair.children.firstOrNull() ?: return null) ?: return null
        return CodeGrammar.ObjectProperty(owner, name)
    }

    override fun property(obj: CodeNode?, name: String): CodeNode? {
        if (obj?.type != OBJECT) return null
        return obj.items().firstNotNullOfOrNull { item ->
            when (item.type) {
                PAIR -> item.children.lastOrNull()?.takeIf { propertyName(item.children.first()) == name }
                SHORTHAND_PROPERTY -> item.takeIf { it.text == name }
                else -> null
            }
        }
    }

    override fun strings(node: CodeNode?): List<String> = when {
        node == null -> emptyList()
        isStringLiteral(node) -> listOf(stringValue(node))
        node.type == ARRAY -> node.items().filter(::isStringLiteral).map(::stringValue)
        else -> emptyList()
    }

    override fun imports(node: CodeNode): Set<String> {
        val root = generateSequence(node) { it.parent }.last()
        imports?.takeIf { it.first == root }?.let { return it.second }
        return IMPORT_SPECIFIER.findAll(root.text).mapTo(mutableSetOf()) { it.groupValues[1] }.also { imports = root to it }
    }

    override fun attributeOf(node: CodeNode): CodeGrammar.Attribute? {
        if (!isStringLiteral(node)) return null
        val attribute = node.parent?.takeIf { it.type == JSX_ATTRIBUTE && it.children.lastOrNull() == node } ?: return null
        val tag = attribute.parent?.takeIf { it.type in JSX_ELEMENT_HEADS } ?: return null
        val name = attribute.children.firstOrNull()?.takeIf { it.type == PROPERTY_IDENTIFIER }?.text ?: return null
        return CodeGrammar.Attribute(tag, tagNameOf(tag), name)
    }

    override fun attributeText(node: CodeNode): String = node.text.unQuote()

    override fun attribute(tag: CodeNode, name: String): CodeGrammar.AttributeValue? {
        val attribute = tag.children.firstOrNull { it.type == JSX_ATTRIBUTE && it.children.firstOrNull()?.text == name } ?: return null
        val value = attribute.children.drop(1).lastOrNull { it.text != "=" } ?: return CodeGrammar.AttributeValue.Bare
        return when {
            isStringLiteral(value) -> CodeGrammar.AttributeValue.Text(value.text.unQuote())
            value.type == JSX_EXPRESSION -> CodeGrammar.AttributeValue.Expression(value.items().singleOrNull() ?: value)
            else -> CodeGrammar.AttributeValue.Expression(value)
        }
    }

    /** The name of a JSX element: `Trans` of `<Trans>`; null for `<Foo.Bar>`, no plain name. */
    private fun tagNameOf(head: CodeNode): String? = head.children.getOrNull(1)?.takeIf { it.type == IDENTIFIER }?.text

    /** The name a pair's key names: an identifier's text, a quoted string's text; null for a computed key. */
    private fun propertyName(key: CodeNode): String? = when {
        key.type == PROPERTY_IDENTIFIER -> key.text
        isStringLiteral(key) -> key.text.unQuote()
        else -> null
    }

    /** The items of an argument list, an array or an object, without their separators. */
    private fun CodeNode.items(): List<CodeNode> = children.filter { it.type !in SEPARATORS }

    companion object {
        const val STRING = "string"
        const val ARGUMENTS = "arguments"
        const val CALL_EXPRESSION = "call_expression"
        const val IDENTIFIER = "identifier"
        const val MEMBER_EXPRESSION = "member_expression"
        const val PARENTHESIZED_EXPRESSION = "parenthesized_expression"
        const val TERNARY_EXPRESSION = "ternary_expression"
        const val OBJECT = "object"
        const val PAIR = "pair"
        const val ARRAY = "array"
        const val SHORTHAND_PROPERTY = "shorthand_property_identifier"
        const val PROPERTY_IDENTIFIER = "property_identifier"
        const val JSX_ATTRIBUTE = "jsx_attribute"
        const val JSX_EXPRESSION = "jsx_expression"
        const val JSX_ELEMENT = "jsx_element"
        const val JSX_OPENING_ELEMENT = "jsx_opening_element"
        const val JSX_CLOSING_ELEMENT = "jsx_closing_element"
        const val JSX_SELF_CLOSING_ELEMENT = "jsx_self_closing_element"
        const val JSX_TEXT = "jsx_text"
        val JSX_ELEMENT_HEADS = setOf(JSX_OPENING_ELEMENT, JSX_SELF_CLOSING_ELEMENT)

        private val SEPARATORS = setOf("(", ")", "[", "]", "{", "}", ",", "comment")
        private val IMPORT_SPECIFIER = Regex("""(?:from|require\(|import)\s*['"]([^'"]+)['"]""")
    }
}
