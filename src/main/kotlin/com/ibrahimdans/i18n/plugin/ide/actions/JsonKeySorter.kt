package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.settings.KeyOrder
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.CodeStyleManager

/**
 * Sorts the keys of a JSON translation file in the project's [KeyOrder], recursively into
 * nested objects.
 *
 * Only the properties that move change. Each object keeps its slots — the whitespace and commas
 * between its properties — and a property out of place is replaced by the one that belongs in its
 * slot, text and all. Indent, spacing and one-line objects stay as written: the whole file used to
 * be reformatted with the IDE's JSON code style after the sort, a diff on every line of a file not
 * indented like the IDE.
 * Standalone comments between entries stay in their slot rather than follow an entry — standard
 * JSON has none, and the plugin targets standard JSON translation files.
 *
 * Shared by [SortI18nKeysAction] (on-demand) and the JSON insertion path (when the
 * `sortKeysAlphabetically` setting keeps files sorted after every key creation).
 *
 * Must be called inside a write action.
 */
object JsonKeySorter {

    fun sort(file: JsonFile, project: Project) {
        val root = file.topLevelValue as? JsonObject ?: return
        val order = Settings.getInstance(project).keyOrder.comparator
        CodeStyleManager.getInstance(project).performActionWithFormatterDisabled { sortObject(root, order) }
    }

    private fun sortObject(obj: JsonObject, order: Comparator<String>) {
        // Depth-first: sort nested objects first so a property copied to another slot carries
        // its value already ordered.
        obj.propertyList.forEach { property ->
            (property.value as? JsonObject)?.let { sortObject(it, order) }
        }

        val props = obj.propertyList
        if (props.size < 2) return

        val sorted = props.sortedWith(compareBy(order) { it.name })
        // Copies taken before any slot is written: a property may be read for one slot after its
        // own slot has been overwritten.
        val copies = sorted.map { it.copy() }
        props.indices
            .filter { props[it] !== sorted[it] }
            .forEach { props[it].replace(copies[it]) }
    }
}
