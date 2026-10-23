package com.ibrahimdans.i18n.plugin.edit

import com.ibrahimdans.i18n.extensions.localization.yaml.YamlScalars
import com.ibrahimdans.i18n.plugin.format.ParsedNodeTree
import com.ibrahimdans.i18n.plugin.format.YamlTranslationTree
import com.ibrahimdans.i18n.plugin.utils.TextSpan

/**
 * The [TranslationEditor] of YAML translation files, read with [YamlTranslationTree] up to their
 * first document.
 *
 * An entry is added at the column of the keys already in its mapping, its value spelled so that it
 * reads back as the same string ([YamlScalars]); the mappings created along a nested key are
 * indented by [indent] per level, the project's YAML indent setting.
 *
 * Block mappings are written; a flow mapping (`{a: 1}`) is sorted, renamed in and deleted from,
 * but gets no insertion. A key without a value (`key:`) is no entry ([YamlTranslationTree]): it is
 * neither found nor moved by [sort].
 */
class YamlTranslationEditor(private val indent: String = "  ") : TranslationEditor {

    override fun insert(source: String, path: List<String>, value: String, order: Comparator<String>?): List<TextEdit> {
        val root = YamlTranslationTree.parse(source) ?: return emptyList()
        val chain = containersOf(root, path) ?: return emptyList()
        val target = chain.last()
        val unresolved = path.drop(chain.size - 1)
        if (isFlow(source, target)) return emptyList()
        val props = target.children()
        if (props.isEmpty()) {
            // A document holding no mapping yet: the entry starts it, at column 0.
            if (chain.size > 1 || !isBlank(source)) return emptyList()
            val newLine = if (source.isEmpty() || source.endsWith('\n')) "" else "\n"
            return listOf(TextEdit.insert(source.length, newLine + entryAt(unresolved, value, 0)))
        }
        val first = entrySpan(props.first()).start
        val column = first - (source.lastIndexOf('\n', first - 1) + 1)
        val entry = entryAt(unresolved, value, column)
        val before = order?.let { props.takeWhile { order.compare(it.value().key.orEmpty(), unresolved.first()) < 0 } } ?: props
        return if (before.isEmpty()) {
            // The first key's indentation sits before it: it now indents the new key.
            listOf(TextEdit.insert(first, entry + "\n" + " ".repeat(column)))
        } else {
            listOf(TextEdit.insert(entrySpan(before.last()).end, "\n" + " ".repeat(column) + entry))
        }
    }

    override fun rename(source: String, path: List<String>, newName: String): List<TextEdit> {
        val node = YamlTranslationTree.parse(source)?.let { nodeAt(it, path) } ?: return emptyList()
        return renameKey(source, node, newName)
    }

    /** Removes the lines of [path] in a block mapping, comments on them included. */
    override fun delete(source: String, path: List<String>): List<TextEdit> {
        val root = YamlTranslationTree.parse(source) ?: return emptyList()
        val node = path.takeIf { it.isNotEmpty() }?.let { nodeAt(root, it) } ?: return emptyList()
        val parent = nodeAt(root, path.dropLast(1)) ?: return emptyList()
        if (isFlow(source, parent)) return deleteSeparated(source, node)
        val span = entrySpan(node)
        val start = source.lastIndexOf('\n', span.start - 1) + 1
        val end = source.indexOf('\n', span.end).let { if (it < 0) source.length else it + 1 }
        return listOf(TextEdit(TextSpan(start, end), ""))
    }

    override fun sort(source: String, order: Comparator<String>): List<TextEdit> {
        val root = YamlTranslationTree.parse(source) ?: return emptyList()
        return sortEdits(source, root, order)
    }

    /**
     * `key: value` as it reads at [column], for the first of [keys]: the other keys nest under it,
     * each [indent] deeper than the one before.
     */
    private fun entryAt(keys: List<String>, value: String, column: Int): String {
        val spelled = YamlScalars.spell(value)
        val rest = keys.drop(1)
        if (rest.isEmpty()) return "${keys.first()}: $spelled"
        val pad = " ".repeat(column) + indent
        return keys.first() + ":" + rest.withIndex().joinToString("") { (i, key) ->
            "\n$pad${indent.repeat(i)}$key:" + if (i == rest.lastIndex) " $spelled" else ""
        }
    }

    private fun isFlow(text: String, mapping: ParsedNodeTree): Boolean = text.getOrNull(mapping.value().valueSpan.start) == '{'

    /** Whether [text] holds nothing but blank lines, comments and document markers. */
    private fun isBlank(text: String): Boolean =
        text.lines().map { it.trim() }.all { it.isEmpty() || it.startsWith('#') || it == "---" }
}
