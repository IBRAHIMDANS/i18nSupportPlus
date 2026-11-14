package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.extensions.localization.arb.ArbMetadata
import com.ibrahimdans.i18n.plugin.ide.settings.KeyOrder
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager

/**
 * Sorts the keys of a JSON translation file in the project's [KeyOrder], recursively into
 * nested objects.
 *
 * Only the order changes. Each object keeps its slots — the whitespace and commas between its
 * properties — and each slot receives the property that belongs there, text and all. Indent, spacing and one-line objects stay as written: the whole file used to
 * be reformatted with the IDE's JSON code style after the sort, a diff on every line of a file not
 * indented like the IDE.
 * Standalone comments between entries stay in their slot rather than follow an entry — standard
 * JSON has none, and the plugin targets standard JSON translation files.
 *
 * The sorted text is built first, then written in one tree change: replacing each property out of
 * place one by one cost a tree change per property — 43 s for a reversed 2,000-key file, and about
 * half of that whenever a key was appended at the end of a sorted file. A file already in order is
 * not touched at all.
 *
 * A Flutter ARB catalogue is sorted by message key, each `@key` metadata block right after its key
 * and the `@@…` entries (`@@locale`) first, in their own order; nothing inside a block moves. Sorted
 * as plain JSON, every block ended up far from the key it describes.
 *
 * The one write replaces the whole object, which sends the caret of an editor showing the file to
 * the start of that range: the caret goes back to the key it was on, at the same place in it.
 *
 * Shared by [SortI18nKeysAction] (on-demand) and the JSON insertion path (when the
 * `sortKeysAlphabetically` setting keeps files sorted after every key creation).
 *
 * Must be called inside a write action.
 */
object JsonKeySorter {

    fun sort(file: JsonFile, project: Project) {
        val root = file.topLevelValue as? JsonObject ?: return
        val keyOrder = Settings.getInstance(project).config().keyOrder.comparator
        val arb = ArbMetadata.isArb(file)
        val order = if (arb) arbOrder(root, keyOrder) else compareBy(keyOrder) { it.name }
        if (isSorted(root, order, nested = !arb)) return
        val sorted = sortedText(root, order, nested = !arb)
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file)
        val carets = document?.let { EditorFactory.getInstance().getEditors(it, project).mapNotNull { editor -> CaretAnchor.of(editor, root) } }.orEmpty()
        CodeStyleManager.getInstance(project).performActionWithFormatterDisabled {
            root.replace(JsonElementGenerator(project).createValue<JsonValue>(sorted))
        }
        if (carets.isEmpty() || document == null) return
        documents.doPostponedOperationsAndUnblockDocument(document)
        val newRoot = file.topLevelValue as? JsonObject ?: return
        carets.forEach { it.restore(newRoot) }
    }

    /**
     * The order of an ARB catalogue's top level: the `@@…` entries first, as written; then each
     * message key in [keyOrder], its `@key` block right after it.
     */
    private fun arbOrder(root: JsonObject, keyOrder: Comparator<String>): Comparator<JsonProperty> {
        val written = root.propertyList.withIndex().associate { (index, property) -> property to index }
        fun isGlobal(property: JsonProperty) = property.name.startsWith("@@")
        fun base(property: JsonProperty) = property.name.removePrefix("@")
        return compareBy<JsonProperty> { if (isGlobal(it)) 0 else 1 }
            .thenComparing { a, b -> if (isGlobal(a)) compareValues(written[a], written[b]) else keyOrder.compare(base(a), base(b)) }
            .thenBy { if (it.name.startsWith("@")) 1 else 0 }
    }

    /** [nested]: nested objects are sorted too — plain JSON; an ARB's `@key` blocks stay as written. */
    private fun isSorted(obj: JsonObject, order: Comparator<JsonProperty>, nested: Boolean): Boolean {
        val props = obj.propertyList
        return props.zipWithNext().all { (a, b) -> order.compare(a, b) <= 0 } &&
            (!nested || props.all { property -> (property.value as? JsonObject)?.let { isSorted(it, order, true) } ?: true })
    }

    /**
     * The text of [obj] with its properties in [order]: every child other than a property — the
     * braces, commas, whitespace and comments — stays where it is, and the n-th property slot
     * receives the n-th property in order, its own nested objects sorted the same way when [nested].
     */
    private fun sortedText(obj: JsonObject, order: Comparator<JsonProperty>, nested: Boolean): String {
        val sorted = obj.propertyList.sortedWith(order).iterator()
        val text = StringBuilder()
        var child = obj.firstChild
        while (child != null) {
            text.append(if (child is JsonProperty) propertyText(sorted.next(), order, nested) else child.text)
            child = child.nextSibling
        }
        return text.toString()
    }

    private fun propertyText(property: JsonProperty, order: Comparator<JsonProperty>, nested: Boolean): String {
        val value = property.value as? JsonObject
        if (value == null || !nested) return property.text
        val start = value.startOffsetInParent
        val text = property.text
        return text.substring(0, start) + sortedText(value, order, true) + text.substring(start + value.textLength)
    }

    /**
     * Where an editor's caret stood before the sort: the chain of keys down to the innermost
     * property holding it, and its distance from that property's start.
     */
    private class CaretAnchor(private val editor: Editor, private val path: List<String>, private val shift: Int) {

        fun restore(root: JsonObject) {
            var obj: JsonObject? = root
            var target: JsonProperty? = null
            for (name in path) {
                target = obj?.findProperty(name) ?: break
                obj = target.value as? JsonObject
            }
            val property = target ?: return
            val range = property.textRange
            editor.caretModel.moveToOffset(range.startOffset + shift.coerceAtMost(range.length))
        }

        companion object {
            fun of(editor: Editor, root: JsonObject): CaretAnchor? {
                val offset = editor.caretModel.offset
                val path = mutableListOf<String>()
                var obj: JsonObject? = root
                var innermost: JsonProperty? = null
                while (obj != null) {
                    val holder = obj.propertyList.firstOrNull { offset in it.textRange.startOffset..it.textRange.endOffset } ?: break
                    path += holder.name
                    innermost = holder
                    obj = holder.value as? JsonObject
                }
                val property = innermost ?: return null
                return CaretAnchor(editor, path, offset - property.textRange.startOffset)
            }
        }
    }
}
