package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSEmbeddedContent
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSParenthesizedExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.TypeScriptSatisfiesExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptAsExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptNotNullExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

/**
 * Extracts msgid from Lingui <Trans>source text</Trans> — the source text IS the key.
 *
 * Unlike key-based approaches (i18n._('key')), Lingui's source-based JSX component uses
 * the visible text directly as the lookup key in .po files.
 *
 * Simple: <Trans>Hello world!</Trans> → msgid "Hello world!"
 *
 * The extractor targets the XmlTag element (<Trans>…</Trans>) rather than its XmlText
 * children. The JSX parser may split text nodes at word boundaries (e.g. "Hello world!"
 * → ["Hello", " ", "world!"]), so attaching to a single XmlText token produces an
 * element whose text does not contain the full msgid; CompositeKeyAnnotatorBase's
 * indexOf(fullKey.source) then returns -1, causing an invalid TextRange.
 *
 * By targeting the XmlTag, element.text = "<Trans>Hello world!</Trans>" and
 * indexOf("Hello world!") correctly locates the msgid within the element text,
 * so the annotation range stays inside the element bounds.
 *
 * The msgid is the message Lingui's macro builds (`babel-plugin-lingui-macro`, `macroJsx.ts`):
 * - a simple identifier keeps its name: `<Trans>Hello {name}!</Trans>` → `Hello {name}!`;
 * - any other expression — member, call, condition — is numbered in order:
 *   `<Trans>Hello {user.name}!</Trans>` → `Hello {0}!`;
 * - a labelled expression takes its label: `{ph({ name: getName() })}` or `{{ name: getName() }}` → `{name}`;
 * - a JSX element becomes an indexed tag, numbered in the order it opens: `<a>docs</a>` → `<0>docs</0>`,
 *   an element without children `<0/>`;
 * - a string literal is text (`{"!"}` → `!`) and a comment `{/* … */}` is dropped;
 * - text follows JSX's whitespace rule: lines are trimmed and joined by one space, blank ones dropped.
 */
class LinguiTransKeyExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is XmlTag) return false
        return element.name == "Trans" && buildMsgid(element).isNotEmpty()
    }

    override fun extract(element: PsiElement): RawKey {
        if (element !is XmlTag) return RawKey(emptyList())
        return RawKey(listOf(KeyElement.literal(buildMsgid(element))))
    }

    private fun buildMsgid(tag: XmlTag): String = MessageBuilder(tag.containingFile.text).apply { appendContent(tag) }.toString()

    /**
     * The msgid of one `<Trans>`, built as the macro walks it: expressions and elements are each
     * numbered by their own counter, shared by the whole message, nested elements included.
     */
    private class MessageBuilder(private val source: String) {
        private val out = StringBuilder()
        private var expressionIndex = 0
        private var elementIndex = 0

        override fun toString(): String = out.toString()

        /**
         * Appends what lies between [tag]'s start and end tags. The text is read from the source
         * between the expressions and elements, as the JSX parser splits it into several tokens.
         *
         * Returns whether the content holds anything the macro keeps — even blank text — which is
         * what writes `<0></0>` rather than `<0/>` for an element.
         */
        fun appendContent(tag: XmlTag): Boolean {
            val content = tag.value.textRange
            var hasChildren = false
            var cursor = content.startOffset
            for (child in tag.children) {
                if (child !is XmlTag && child !is JSEmbeddedContent) continue
                val range = child.textRange
                if (!content.contains(range)) continue
                hasChildren = appendText(cursor, range.startOffset) or hasChildren
                hasChildren = appendChild(child) or hasChildren
                cursor = range.endOffset
            }
            return appendText(cursor, content.endOffset) or hasChildren
        }

        private fun appendText(start: Int, end: Int): Boolean {
            if (start >= end) return false
            out.append(cleanJsxText(source.substring(start, end)))
            return true
        }

        private fun appendChild(child: PsiElement): Boolean {
            if (child is XmlTag) {
                appendElement(child)
                return true
            }
            // `{/* comment */}` holds no expression: the macro skips it without consuming an index.
            val expression = PsiTreeUtil.getChildOfType(child, JSExpression::class.java) ?: return false
            appendExpression(expression)
            return true
        }

        private fun appendElement(tag: XmlTag) {
            val name = elementIndex++
            val start = out.length
            out.append('<').append(name).append('>')
            if (appendContent(tag)) {
                out.append("</").append(name).append('>')
            } else {
                out.setLength(start)
                out.append('<').append(name).append("/>")
            }
        }

        private fun appendExpression(expression: JSExpression) {
            val inner = unwrapParentheses(expression)
            when {
                inner is XmlTag -> appendElement(inner)
                inner is JSLiteralExpression && inner.isStringLiteral -> out.append(inner.stringValue.orEmpty())
                else -> out.append('{').append(placeholderName(inner)).append('}')
            }
        }

        /**
         * The name the macro gives an expression (`expressionToArgument`): an identifier's own name,
         * a label's, otherwise the next index. TypeScript's `as`, `satisfies` and `!` are looked
         * through, as the macro does: `{name!}` is still `{name}`.
         */
        private fun placeholderName(expression: JSExpression): String {
            val inner = unwrapTypeScript(expression)
            if (inner is JSReferenceExpression && inner.qualifier == null) inner.referenceName?.let { return it }
            return labelOf(inner) ?: (expressionIndex++).toString()
        }

        /** `{ name: value }` or `ph({ name: value })`: one property named by an identifier. */
        private fun labelOf(expression: JSExpression): String? {
            val labelled = when (expression) {
                is JSObjectLiteralExpression -> expression
                is JSCallExpression -> expression.takeIf { it.methodExpression?.text == "ph" }
                    ?.arguments?.firstOrNull() as? JSObjectLiteralExpression
                else -> null
            } ?: return null
            val property = labelled.properties.singleOrNull() ?: return null
            // A quoted key (`{ "name": value }`) is no identifier: the macro rejects it.
            return property.name?.takeIf { it == property.nameIdentifier?.text }
        }

        private fun unwrapParentheses(expression: JSExpression): JSExpression =
            if (expression is JSParenthesizedExpression) expression.innerExpression?.let(::unwrapParentheses) ?: expression
            else expression

        private fun unwrapTypeScript(expression: JSExpression): JSExpression {
            val inner = when (expression) {
                is JSParenthesizedExpression -> expression.innerExpression
                is TypeScriptAsExpression -> expression.expression
                is TypeScriptSatisfiesExpression -> expression.expression
                is TypeScriptNotNullExpression -> expression.expression
                else -> return expression
            }
            return inner?.let(::unwrapTypeScript) ?: expression
        }
    }

    private companion object {
        val LINE_BREAK = Regex("\r\n|\n|\r")

        /**
         * JSX's whitespace rule for a text child, as Babel's `cleanJSXElementLiteralChild` applies
         * it and the macro reuses it: tabs become spaces, each line is trimmed where it touches a
         * line break, and the non-blank lines are joined by one space.
         */
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
