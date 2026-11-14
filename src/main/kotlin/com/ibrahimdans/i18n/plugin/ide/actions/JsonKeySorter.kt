package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.settings.KeyOrder
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.project.Project
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
 * Shared by [SortI18nKeysAction] (on-demand) and the JSON insertion path (when the
 * `sortKeysAlphabetically` setting keeps files sorted after every key creation).
 *
 * Must be called inside a write action.
 */
object JsonKeySorter {

    fun sort(file: JsonFile, project: Project) {
        val root = file.topLevelValue as? JsonObject ?: return
        val order = Settings.getInstance(project).config().keyOrder.comparator
        if (isSorted(root, order)) return
        val sorted = sortedText(root, order)
        CodeStyleManager.getInstance(project).performActionWithFormatterDisabled {
            root.replace(JsonElementGenerator(project).createValue<JsonValue>(sorted))
        }
    }

    private fun isSorted(obj: JsonObject, order: Comparator<String>): Boolean {
        val props = obj.propertyList
        return props.zipWithNext().all { (a, b) -> order.compare(a.name, b.name) <= 0 } &&
            props.all { (it.value as? JsonObject)?.let { nested -> isSorted(nested, order) } ?: true }
    }

    /**
     * The text of [obj] with its properties in [order]: every child other than a property — the
     * braces, commas, whitespace and comments — stays where it is, and the n-th property slot
     * receives the n-th property in order, its own nested objects sorted the same way.
     */
    private fun sortedText(obj: JsonObject, order: Comparator<String>): String {
        val sorted = obj.propertyList.sortedWith(compareBy(order) { it.name }).iterator()
        val text = StringBuilder()
        var child = obj.firstChild
        while (child != null) {
            text.append(if (child is JsonProperty) propertyText(sorted.next(), order) else child.text)
            child = child.nextSibling
        }
        return text.toString()
    }

    private fun propertyText(property: JsonProperty, order: Comparator<String>): String {
        val value = property.value as? JsonObject ?: return property.text
        val start = value.startOffsetInParent
        val text = property.text
        return text.substring(0, start) + sortedText(value, order) + text.substring(start + value.textLength)
    }
}
