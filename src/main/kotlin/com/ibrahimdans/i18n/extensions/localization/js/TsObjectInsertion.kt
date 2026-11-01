package com.ibrahimdans.i18n.extensions.localization.js

import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSSpreadExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

/**
 * Writes a property into [obj], an object literal of a TS/JS catalogue, in the layout [obj] already
 * has, read from the file's text: the indent its properties start at, whether it holds them on one
 * line, its quotes and its trailing commas. Runs inside a write action, with the formatter disabled:
 * the IDE's JavaScript code style (4 spaces by default) is not the catalogue's.
 *
 * The same rules as the JSON writer ([com.ibrahimdans.i18n.extensions.localization.json.JsonInsertion]),
 * plus what JSON does not have: a comma may follow the last property, and the quote is the file's.
 */
internal class TsObjectInsertion(private val obj: JSObjectLiteralExpression) {

    private val file: PsiFile = obj.containingFile
    private val text = file.text
    private val quote = quoteOf(file)
    private val trailingComma = TRAILING_COMMA.containsMatchIn(text)
    private val layout = layoutOf(obj)

    /**
     * Inserts the property named by the first of [segments], holding [value] under the others, after
     * the last of the existing properties [preceding] keeps, or in front of them all when it keeps none.
     */
    fun insert(segments: List<String>, value: String, preceding: (List<JSProperty>) -> List<JSProperty>) {
        val entry = render(segments, value, layout.childIndent)
        val props = obj.properties.toList()
        if (props.isEmpty()) {
            insertIntoEmpty(entry)
            return
        }
        val before = preceding(props)
        if (before.isEmpty()) {
            // `entry,<separator>` in front of the first property, after the whitespace preceding it.
            val dummy = dummyObject("$entry,${layout.separator}_: 0")
            obj.addRangeBefore(dummy.properties.first(), dummy.properties.last().prevSibling, props.first())
            return
        }
        val previous = before.last()
        val comma = commaAfter(previous)
        if (comma != null) {
            // `previous,<separator>entry,`: the comma after `previous` stays, the entry brings its own.
            val dummy = dummyObject("_: 0,${layout.separator}$entry,")
            obj.addRangeAfter(commaAfter(dummy.properties.first())!!.nextSibling, commaAfter(dummy.properties.last())!!, comma)
        } else {
            // `previous,<separator>entry`: the last property, written without a trailing comma.
            val dummy = dummyObject("_: 0,${layout.separator}$entry")
            obj.addRangeAfter(commaAfter(dummy.properties.first())!!, dummy.properties.last(), previous)
        }
    }

    /** `{}` becomes `{<open>entry<close>}`, whatever whitespace it held. */
    private fun insertIntoEmpty(entry: String) {
        val open = obj.firstChild
        val close = obj.lastChild
        if (open.nextSibling != close) obj.deleteChildRange(open.nextSibling, close.prevSibling)
        val trailing = if (layout.multiLine && trailingComma) "," else ""
        val dummy = dummyObject("${layout.open}$entry$trailing${layout.close}")
        obj.addRangeAfter(dummy.firstChild.nextSibling, dummy.lastChild.prevSibling, open)
    }

    /** `key: value`, the value nested under the other [segments] and laid out like [obj], on a line indented by [indent]. */
    private fun render(segments: List<String>, value: String, indent: String): String {
        val key = keyText(segments.first())
        if (segments.size == 1) return "$key: ${quoted(value)}"
        if (!layout.multiLine) return "$key: {${layout.open}${render(segments.drop(1), value, indent)}${layout.close}}"
        val inner = indent + layout.unit
        val trailing = if (trailingComma) "," else ""
        return "$key: {\n$inner${render(segments.drop(1), value, inner)}$trailing\n$indent}"
    }

    /** A key as an identifier when it is one, quoted otherwise (`'save-button'`, `'2fa'`). */
    private fun keyText(name: String): String = if (IDENTIFIER.matches(name)) name else quoted(name)

    /** [value] as a string literal in the file's quote. */
    private fun quoted(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace(quote, "\\$quote")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "$quote$escaped$quote"
    }

    /**
     * The layout of [target]'s properties:
     * - on several lines: each on its own line, indented like the existing ones;
     * - on one line: joined by the whitespace that follows their commas, inside braces padded like
     *   [target]'s own;
     * - none yet: the layout of the object holding [target] when that one is on one line, otherwise
     *   several lines one indent unit of the file deeper than the line of `{`.
     */
    private fun layoutOf(target: JSObjectLiteralExpression): Layout {
        val props = target.properties
        val indent = lineIndent(target.textRange.startOffset)
        if (props.isEmpty()) {
            val parent = PsiTreeUtil.getParentOfType(target, JSObjectLiteralExpression::class.java)
            if (parent != null && !parent.textContains('\n')) return layoutOf(parent)
            val child = indent + fileUnit()
            return Layout(true, child, fileUnit(), "\n$child", "\n$child", "\n$indent")
        }
        if (!target.textContains('\n')) {
            val separator = props.getOrNull(1)?.let { whitespaceBefore(it) } ?: " "
            val open = whitespaceBefore(props.first())
            val close = (target.lastChild.prevSibling as? PsiWhiteSpace)?.text ?: ""
            return Layout(false, "", "", separator, open, close)
        }
        // The indent of the last property that starts a line: one sharing the line of `{` says
        // nothing of where the others go.
        val child = props.lastOrNull { whitespaceBefore(it).contains('\n') }
            ?.let { whitespaceBefore(it).substringAfterLast('\n') }
            ?: (indent + fileUnit())
        val unit = child.removePrefix(indent).takeIf { child.startsWith(indent) && it.isNotEmpty() } ?: fileUnit()
        return Layout(true, child, unit, "\n$child", "\n$child", "\n$indent")
    }

    private fun whitespaceBefore(element: PsiElement): String = (element.prevSibling as? PsiWhiteSpace)?.text ?: ""

    /** The leading spaces and tabs of the line holding [offset]. */
    private fun lineIndent(offset: Int): String {
        val start = text.lastIndexOf('\n', offset - 1) + 1
        return text.substring(start, offset).takeWhile { it == ' ' || it == '\t' }
    }

    /**
     * One level of indent in this file: a tab when lines are indented with tabs, otherwise the
     * narrowest indent of any line; 2 spaces in a file without an indented line.
     */
    private fun fileUnit(): String {
        val indents = text.lineSequence()
            .filter { it.isNotBlank() }
            .map { line -> line.takeWhile { it == ' ' || it == '\t' } }
            .filter { it.isNotEmpty() }
            .toList()
        if (indents.any { it.startsWith('\t') }) return "\t"
        return indents.minOfOrNull { it.length }?.let { " ".repeat(it) } ?: DEFAULT_UNIT
    }

    /** The object literal of `x = {content};`, parsed in the language of the catalogue. */
    private fun dummyObject(content: String): JSObjectLiteralExpression {
        val dummy = PsiFileFactory.getInstance(file.project)
            .createFileFromText("dummy.${file.virtualFile?.extension ?: "ts"}", file.language, "x = {$content};")
        return PsiTreeUtil.findChildOfType(dummy, JSObjectLiteralExpression::class.java)!!
    }

    /**
     * [childIndent] starts each property line of a multi-line object, [unit] is one level deeper;
     * [separator] goes between a comma and the next property; [open] and [close] go inside the
     * braces of an object written from scratch.
     */
    private data class Layout(
        val multiLine: Boolean,
        val childIndent: String,
        val unit: String,
        val separator: String,
        val open: String,
        val close: String,
    )

    companion object {
        private const val DEFAULT_UNIT = "  "
        private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

        /** A comma closing the last line of an object or an array: the file writes trailing commas. */
        private val TRAILING_COMMA = Regex(",[ \\t]*\\r?\\n[ \\t]*[}\\]]")

        /** The comma right after [element], comments and whitespace aside, or null. */
        internal fun commaAfter(element: PsiElement): PsiElement? =
            PsiTreeUtil.skipWhitespacesAndCommentsForward(element)?.takeIf { it.text == "," }

        /** The quote the file's first string literal uses; a single quote when it has none. */
        private fun quoteOf(file: PsiFile): String =
            PsiTreeUtil.findChildrenOfType(file, JSLiteralExpression::class.java)
                .firstNotNullOfOrNull { literal -> literal.text.firstOrNull()?.takeIf { it == '\'' || it == '"' } }
                ?.toString()
                ?: "'"

        /**
         * Whether [obj] is built in code rather than written out: a spread (`...common`) or a
         * computed key (`[KEY]: …`) hides keys the plugin cannot read, so one written next to them
         * may shadow, or be shadowed by, the one the application really uses.
         */
        fun isBuilt(obj: JSObjectLiteralExpression): Boolean =
            obj.children.any { it is JSSpreadExpression } || obj.properties.any { it.text.startsWith("[") }
    }
}
