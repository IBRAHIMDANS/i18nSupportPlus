package com.ibrahimdans.i18n.extract.lingui

import com.ibrahimdans.i18n.extract.rules.TsxGrammar
import com.ibrahimdans.i18n.plugin.parser.CodeKeyExtractor
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.unQuote

/**
 * The msgid of Lingui's source-text `<Trans>Hello world!</Trans>`, on a tree-sitter TSX tree: the
 * plugin's `LinguiTransKeyExtractor`, whose rules are the macro's (`babel-plugin-lingui-macro`,
 * `macroJsx.ts`):
 *  - a simple identifier keeps its name: `<Trans>Hello {name}!</Trans>` → `Hello {name}!`;
 *  - any other expression — member, call, condition — is numbered in order: `Hello {user.name}!` → `Hello {0}!`;
 *  - a labelled expression takes its label: `{ph({ name: getName() })}` or `{{ name: getName() }}` → `{name}`;
 *  - a JSX element becomes an indexed tag, numbered in the order it opens: `<a>docs</a>` → `<0>docs</0>`, one without children `<0/>`;
 *  - a string literal is text (`{"!"}` → `!`) and a comment `{/* … */}` is dropped;
 *  - text follows JSX's whitespace rule: lines are trimmed and joined by one space, blank ones dropped.
 *
 * Not a rule of `FrameworkRules`: the key is built from the element's whole content, which no
 * declaration of a function name and an argument can say. The node read is the `<Trans>` element.
 */
class LinguiTransMsgidExtractor : CodeKeyExtractor {

    override fun canExtract(node: CodeNode): Boolean = extract(node) != null

    override fun extract(node: CodeNode): RawKey? {
        if (node.type != TsxGrammar.JSX_ELEMENT || tagNameOf(node) != TRANS) return null
        val msgid = MessageBuilder().apply { appendContent(node) }.toString()
        if (msgid.isEmpty()) return null
        // The msgid is the source text: a key created for it must not be flagged as an explicit id.
        return RawKey(listOf(KeyElement.literal(msgid)), sourceText = true)
    }

    private fun tagNameOf(element: CodeNode): String? =
        element.children.firstOrNull()?.takeIf { it.type == TsxGrammar.JSX_OPENING_ELEMENT }
            ?.children?.getOrNull(1)?.takeIf { it.type == TsxGrammar.IDENTIFIER }?.text

    /** The msgid of one `<Trans>`, built as the macro walks it: expressions and elements each numbered by one counter shared by the whole message. */
    private class MessageBuilder {
        private val out = StringBuilder()
        private var expressionIndex = 0
        private var elementIndex = 0

        override fun toString(): String = out.toString()

        /** Appends what lies between [element]'s tags; true when it holds anything the macro keeps — even blank text — which writes `<0></0>` rather than `<0/>`. */
        fun appendContent(element: CodeNode): Boolean {
            var hasChildren = false
            for (child in element.children) {
                when (child.type) {
                    TsxGrammar.JSX_OPENING_ELEMENT, TsxGrammar.JSX_CLOSING_ELEMENT -> {}
                    TsxGrammar.JSX_TEXT -> { out.append(cleanJsxText(child.text)); hasChildren = true }
                    TsxGrammar.JSX_EXPRESSION -> hasChildren = appendExpression(child) or hasChildren
                    TsxGrammar.JSX_ELEMENT, TsxGrammar.JSX_SELF_CLOSING_ELEMENT -> { appendElement(child); hasChildren = true }
                    else -> {}
                }
            }
            return hasChildren
        }

        /** The expression of a `{…}`: a comment holds none and consumes no index. */
        private fun appendExpression(braced: CodeNode): Boolean {
            val expression = braced.children.firstOrNull { it.text != "{" && it.text != "}" && it.type != COMMENT } ?: return false
            val inner = unwrapParentheses(expression)
            when {
                inner.type == TsxGrammar.JSX_ELEMENT || inner.type == TsxGrammar.JSX_SELF_CLOSING_ELEMENT -> appendElement(inner)
                inner.type == TsxGrammar.STRING -> out.append(inner.text.unQuote())
                else -> out.append('{').append(placeholderName(inner)).append('}')
            }
            return true
        }

        private fun appendElement(element: CodeNode) {
            val name = elementIndex++
            val start = out.length
            out.append('<').append(name).append('>')
            if (element.type == TsxGrammar.JSX_ELEMENT && appendContent(element)) {
                out.append("</").append(name).append('>')
            } else {
                out.setLength(start)
                out.append('<').append(name).append("/>")
            }
        }

        /** An identifier's own name, a label's, otherwise the next index; TypeScript's `as`, `satisfies` and `!` are looked through, as the macro does. */
        private fun placeholderName(expression: CodeNode): String {
            val inner = unwrapTypeScript(expression)
            if (inner.type == TsxGrammar.IDENTIFIER) return inner.text
            return labelOf(inner) ?: (expressionIndex++).toString()
        }

        /** `{ name: value }` or `ph({ name: value })`: one property named by an identifier. */
        private fun labelOf(expression: CodeNode): String? {
            val labelled = when (expression.type) {
                TsxGrammar.OBJECT -> expression
                TsxGrammar.CALL_EXPRESSION -> expression.takeIf { it.children.firstOrNull()?.text == PH }
                    ?.children?.lastOrNull { it.type == TsxGrammar.ARGUMENTS }?.items()?.firstOrNull()?.takeIf { it.type == TsxGrammar.OBJECT }
                else -> null
            } ?: return null
            val property = labelled.items().singleOrNull()?.takeIf { it.type == TsxGrammar.PAIR } ?: return null
            // A quoted key (`{ "name": value }`) is no identifier: the macro rejects it.
            return property.children.firstOrNull()?.takeIf { it.type == TsxGrammar.PROPERTY_IDENTIFIER }?.text
        }

        private fun unwrapParentheses(expression: CodeNode): CodeNode =
            if (expression.type == TsxGrammar.PARENTHESIZED_EXPRESSION) expression.items().singleOrNull()?.let(::unwrapParentheses) ?: expression
            else expression

        private fun unwrapTypeScript(expression: CodeNode): CodeNode {
            val inner = when (expression.type) {
                TsxGrammar.PARENTHESIZED_EXPRESSION -> expression.items().singleOrNull()
                AS_EXPRESSION, SATISFIES_EXPRESSION, NON_NULL_EXPRESSION -> expression.children.firstOrNull()
                else -> return expression
            }
            return inner?.let(::unwrapTypeScript) ?: expression
        }

        private fun CodeNode.items(): List<CodeNode> = children.filter { it.type !in SEPARATORS }
    }

    private companion object {
        const val TRANS = "Trans"
        const val PH = "ph"
        const val COMMENT = "comment"
        const val AS_EXPRESSION = "as_expression"
        const val SATISFIES_EXPRESSION = "satisfies_expression"
        const val NON_NULL_EXPRESSION = "non_null_expression"
        val SEPARATORS = setOf("(", ")", "[", "]", "{", "}", ",", COMMENT)
        val LINE_BREAK = Regex("\r\n|\n|\r")

        /** JSX's whitespace rule for a text child, as Babel's `cleanJSXElementLiteralChild` applies it: tabs become spaces, each line is trimmed where it touches a line break, non-blank lines joined by one space. */
        fun cleanJsxText(text: String): String {
            val lines = text.split(LINE_BREAK)
            val lastNonBlank = lines.indexOfLast { line -> line.any { it != ' ' && it != '\t' } }.coerceAtLeast(0)
            val cleaned = StringBuilder()
            lines.forEachIndexed { i, line ->
                var trimmed = line.replace('\t', ' ')
                if (i != 0) trimmed = trimmed.trimStart(' ')
                if (i != lines.lastIndex) trimmed = trimmed.trimEnd(' ')
                if (trimmed.isNotEmpty()) {
                    cleaned.append(trimmed)
                    if (i != lastNonBlank) cleaned.append(' ')
                }
            }
            return cleaned.toString()
        }
    }
}
