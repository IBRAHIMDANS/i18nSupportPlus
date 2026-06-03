package com.ibrahimdans.i18n.plugin.ide.actions

import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.CodeStyleManager

/**
 * Sorts the keys of a JSON translation file alphabetically (case-insensitive), recursively
 * into nested objects.
 *
 * Conservative: each entry keeps its own text (value subtree and any inline content); only the
 * order of properties changes, and the file is reformatted afterwards to restore indentation.
 * Standalone comments between entries are not preserved — standard JSON has none, and the plugin
 * targets standard JSON translation files.
 *
 * Shared by [SortI18nKeysAction] (on-demand) and the JSON insertion path (when the
 * `sortKeysAlphabetically` setting keeps files sorted after every key creation).
 *
 * Must be called inside a write action.
 */
object JsonKeySorter {

    fun sort(file: JsonFile, project: Project) {
        val root = file.topLevelValue as? JsonObject ?: return
        sortObject(root, JsonElementGenerator(project))
        CodeStyleManager.getInstance(project).reformat(file)
    }

    private fun sortObject(obj: JsonObject, generator: JsonElementGenerator) {
        // Depth-first: sort nested objects first so their serialized text is already ordered
        // by the time the parent is rebuilt.
        obj.propertyList.forEach { property ->
            (property.value as? JsonObject)?.let { sortObject(it, generator) }
        }

        val props = obj.propertyList
        if (props.size < 2) return

        val sorted = props.sortedBy { it.name.lowercase() }
        if (sorted.map { it.name } == props.map { it.name }) return

        val body = sorted.joinToString(",\n") { it.text }
        val newObject = generator.createValue<JsonObject>("{\n$body\n}")
        obj.replace(newObject)
    }
}
