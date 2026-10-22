package com.ibrahimdans.i18n.plugin.edit

import com.ibrahimdans.i18n.plugin.format.ParsedNodeTree
import com.ibrahimdans.i18n.plugin.utils.TextSpan

/**
 * Writes the keys of a translation file as text: each operation reads [source], the whole file,
 * and answers the [TextEdit]s that make the change — what a language server hands back — without
 * a PSI or a write action. [TextEdit.apply] gives the file after the change.
 *
 * A key is its path of segments, from the root object down: `["menu", "save"]` for `menu.save`.
 * An operation that cannot apply — the file does not parse, the key is missing, or already there —
 * answers no edit at all; the file is never left half-written.
 */
interface TranslationEditor {

    /**
     * Adds [path] with the string [value], creating the objects of [path] the file lacks. The new
     * entry goes in the deepest existing object of [path], laid out like that object, after the
     * last entry [order] puts before it — after them all when [order] is null.
     */
    fun insert(source: String, path: List<String>, value: String, order: Comparator<String>? = null): List<TextEdit>

    /** Renames the last segment of [path] to [newName], in the quotes the key is written in. */
    fun rename(source: String, path: List<String>, newName: String): List<TextEdit>

    /** Removes [path] and its value, with what separated it from the entries around it. */
    fun delete(source: String, path: List<String>): List<TextEdit>

    /**
     * Puts the entries of every object in [order], nested ones included. Only the entries that
     * move change: each object keeps its slots — the separators between its entries — and an
     * entry out of place is replaced by the one that belongs in its slot, text and all.
     */
    fun sort(source: String, order: Comparator<String>): List<TextEdit>
}

/** The node at [path] under [root], or null. */
internal fun nodeAt(root: ParsedNodeTree, path: List<String>): ParsedNodeTree? =
    path.fold(root as ParsedNodeTree?) { node, name -> node?.findChild(name) }

/**
 * The objects [path] goes through under [root], from [root] down to the deepest one that exists:
 * the entry to insert goes in the last one. Null when [path] exists already or runs into a leaf.
 */
internal fun containersOf(root: ParsedNodeTree, path: List<String>): List<ParsedNodeTree>? {
    val chain = mutableListOf(root)
    for (name in path) {
        val child = chain.last().findChild(name) ?: return chain
        if (child.isLeaf()) return null
        chain += child
    }
    return null
}

/** What [node] spans as an entry of its object: from its key to the end of its value. */
internal fun entrySpan(node: ParsedNodeTree): TextSpan = TextSpan(node.value().keySpan!!.start, node.value().valueSpan.end)

/** [node]'s key, in [text], written [newName] within the quotes it had. */
internal fun renameKey(text: String, node: ParsedNodeTree, newName: String): List<TextEdit> {
    val span = node.value().keySpan ?: return emptyList()
    val raw = text.substring(span.start, span.end)
    val quote = raw.firstOrNull()?.takeIf { it in QUOTES && raw.length > 1 && raw.last() == it }
    return listOf(TextEdit(span, if (quote != null) "$quote$newName$quote" else newName))
}

private const val QUOTES = "\"'"

/**
 * The edits sorting [obj] and the objects under it, in [text]: depth first, so that an entry
 * copied into another slot carries its value already sorted.
 */
internal fun sortEdits(text: String, obj: ParsedNodeTree, order: Comparator<String>): List<TextEdit> {
    val entries = obj.children()
    val sorted = entries.sortedWith(compareBy(order) { it.value().key.orEmpty() })
    return entries.indices.flatMap { i ->
        if (entries[i] === sorted[i]) sortEdits(text, entries[i], order)
        else listOf(TextEdit(entrySpan(entries[i]), sortedText(text, sorted[i], order)))
    }
}

/** The text of the entry [node], its value sorted. */
private fun sortedText(text: String, node: ParsedNodeTree, order: Comparator<String>): String {
    val span = entrySpan(node)
    val inner = sortEdits(text, node, order).map { TextEdit(TextSpan(it.span.start - span.start, it.span.end - span.start), it.newText) }
    return TextEdit.apply(text.substring(span.start, span.end), inner)
}

/** The offset of the last non-whitespace character of [text] before [offset], or -1. */
internal fun previousNonBlank(text: String, offset: Int): Int {
    var i = offset - 1
    while (i >= 0 && text[i].isWhitespace()) i--
    return i
}

/** The offset of the first non-whitespace character of [text] from [offset], or its length. */
internal fun nextNonBlank(text: String, offset: Int): Int {
    var i = offset
    while (i < text.length && text[i].isWhitespace()) i++
    return i
}

/**
 * Removes the entry [node] from a comma-separated object — JSON, a YAML flow mapping: with the
 * comma before it, or the one after it when it comes first; the only entry leaves `{}`.
 */
internal fun deleteSeparated(text: String, node: ParsedNodeTree): List<TextEdit> {
    val span = entrySpan(node)
    val before = previousNonBlank(text, span.start)
    if (before >= 0 && text[before] == ',') return listOf(TextEdit(TextSpan(before, span.end), ""))
    val after = nextNonBlank(text, span.end)
    if (after < text.length && text[after] == ',') return listOf(TextEdit(TextSpan(span.start, nextNonBlank(text, after + 1)), ""))
    return listOf(TextEdit(TextSpan(before + 1, after), ""))
}
