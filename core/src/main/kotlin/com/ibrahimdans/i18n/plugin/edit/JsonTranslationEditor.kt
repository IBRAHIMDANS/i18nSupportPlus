package com.ibrahimdans.i18n.plugin.edit

import com.fasterxml.jackson.core.io.JsonStringEncoder
import com.ibrahimdans.i18n.plugin.format.JsonTranslationTree
import com.ibrahimdans.i18n.plugin.format.ParsedNodeTree
import com.ibrahimdans.i18n.plugin.utils.TextSpan

/**
 * The [TranslationEditor] of JSON translation files, read with [JsonTranslationTree].
 *
 * An entry is added in the layout of the object it goes in, read from the file's text: the indent
 * of its properties (spaces or tabs, whatever their width), on one line when the object is on one
 * line; the objects created along a nested key are laid out the same way. Nothing else changes.
 */
object JsonTranslationEditor : TranslationEditor {

    private const val DEFAULT_UNIT = "  "

    override fun insert(source: String, path: List<String>, value: String, order: Comparator<String>?): List<TextEdit> {
        val root = JsonTranslationTree.parse(source) ?: return emptyList()
        val chain = containersOf(root, path) ?: return emptyList()
        val target = chain.last()
        val unresolved = path.drop(chain.size - 1)
        val layout = layoutOf(source, chain)
        val entry = render(unresolved, quote(value), layout.childIndent, layout)
        val props = target.children()
        val span = target.value().valueSpan
        if (props.isEmpty()) {
            // `{}` becomes `{<open>"key": value<close>}`, whatever whitespace it held.
            return listOf(TextEdit(TextSpan(span.start + 1, span.end - 1), "${layout.open}$entry${layout.close}"))
        }
        val before = order?.let { props.takeWhile { order.compare(it.value().key.orEmpty(), unresolved.first()) < 0 } } ?: props
        return if (before.isEmpty()) {
            // `"key": value,<separator>` in front of the first property, after the whitespace before it.
            listOf(TextEdit.insert(entrySpan(props.first()).start, "$entry,${layout.separator}"))
        } else {
            // `,<separator>"key": value` right after the property it follows.
            listOf(TextEdit.insert(entrySpan(before.last()).end, ",${layout.separator}$entry"))
        }
    }

    override fun rename(source: String, path: List<String>, newName: String): List<TextEdit> {
        val node = JsonTranslationTree.parse(source)?.let { nodeAt(it, path) } ?: return emptyList()
        return renameKey(source, node, newName)
    }

    override fun delete(source: String, path: List<String>): List<TextEdit> {
        val root = JsonTranslationTree.parse(source) ?: return emptyList()
        val node = path.takeIf { it.isNotEmpty() }?.let { nodeAt(root, it) } ?: return emptyList()
        return deleteSeparated(source, node)
    }

    override fun sort(source: String, order: Comparator<String>): List<TextEdit> {
        val root = JsonTranslationTree.parse(source) ?: return emptyList()
        return sortEdits(source, root, order)
    }

    /** [text] as a JSON string literal, quotes included. */
    private fun quote(text: String): String = "\"${String(JsonStringEncoder.getInstance().quoteAsString(text))}\""

    /**
     * The property `"<keys[0]>": …` holding [literal] under the chain of objects of the other
     * [keys], each laid out like [layout], on a line indented by [indent].
     */
    private fun render(keys: List<String>, literal: String, indent: String, layout: Layout): String {
        val name = quote(keys.first())
        val rest = keys.drop(1)
        if (rest.isEmpty()) return "$name: $literal"
        if (!layout.multiLine) return "$name: {${layout.open}${render(rest, literal, indent, layout)}${layout.close}}"
        val inner = indent + layout.unit
        return "$name: {\n$inner${render(rest, literal, inner, layout)}\n$indent}"
    }

    /**
     * The layout of the properties of the last object of [chain], which holds the objects above it:
     * - on several lines: each on its own line, indented like the existing ones;
     * - on one line: joined by the whitespace that follows their commas, inside braces padded
     *   like the object's own;
     * - none yet: the layout of the object holding it when that one is on one line, otherwise
     *   several lines one indent unit of the file deeper than the line of `{`.
     */
    private fun layoutOf(text: String, chain: List<ParsedNodeTree>): Layout {
        val target = chain.last()
        val props = target.children()
        val span = target.value().valueSpan
        val indent = lineIndent(text, span.start)
        val unit by lazy { fileUnit(text) }
        if (props.isEmpty()) {
            val parent = chain.getOrNull(chain.size - 2)
            if (parent != null && isOneLine(text, parent.value().valueSpan)) return layoutOf(text, chain.dropLast(1))
            val child = indent + unit
            return Layout(true, child, unit, "\n$child", "\n$child", "\n$indent")
        }
        if (isOneLine(text, span)) {
            val separator = props.getOrNull(1)?.let { whitespaceBefore(text, entrySpan(it).start) } ?: " "
            val open = whitespaceBefore(text, entrySpan(props.first()).start)
            val close = whitespaceBefore(text, span.end - 1)
            return Layout(false, "", "", separator, open, close)
        }
        // The indent of the last property that starts a line: one sharing the line of `{` says
        // nothing of where the others go.
        val child = props.map { whitespaceBefore(text, entrySpan(it).start) }.lastOrNull { it.contains('\n') }
            ?.substringAfterLast('\n')
            ?: (indent + unit)
        val childUnit = child.removePrefix(indent).takeIf { child.startsWith(indent) && it.isNotEmpty() } ?: unit
        return Layout(true, child, childUnit, "\n$child", "\n$child", "\n$indent")
    }

    private fun isOneLine(text: String, span: TextSpan): Boolean = text.indexOf('\n', span.start).let { it < 0 || it >= span.end }

    /** The whitespace right before [offset]. */
    private fun whitespaceBefore(text: String, offset: Int): String = text.substring(previousNonBlank(text, offset) + 1, offset)

    /** The leading spaces and tabs of the line holding [offset]. */
    private fun lineIndent(text: String, offset: Int): String {
        val start = text.lastIndexOf('\n', offset - 1) + 1
        return text.substring(start, offset).takeWhile { it == ' ' || it == '\t' }
    }

    /**
     * One level of indent in [text]: a tab when lines are indented with tabs, otherwise the
     * narrowest indent of any line; 2 spaces in a file without an indented line.
     */
    private fun fileUnit(text: String): String {
        val indents = text.lineSequence()
            .filter { it.isNotBlank() }
            .map { line -> line.takeWhile { it == ' ' || it == '\t' } }
            .filter { it.isNotEmpty() }
            .toList()
        if (indents.any { it.startsWith('\t') }) return "\t"
        return indents.minOfOrNull { it.length }?.let { " ".repeat(it) } ?: DEFAULT_UNIT
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
}
