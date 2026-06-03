package com.ibrahimdans.i18n.plugin.ide.actions

import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.CodeStyleManager

/**
 * Sorts the keys of a JSON translation file alphabetically (case-insensitive), recursively
 * into nested objects.
 *
 * Conservative by design: each entry keeps its own text (value subtree and any inline content);
 * only the order of properties changes, and the file is reformatted afterwards to restore
 * indentation. Standalone comments between entries are not preserved — standard JSON has none,
 * and the plugin targets standard JSON translation files. YAML sorting is intentionally out of
 * scope here (block indentation makes a safe reorder a separate problem).
 */
class SortI18nKeysAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.PSI_FILE) is JsonFile
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) as? JsonFile ?: return
        WriteCommandAction.runWriteCommandAction(
            project,
            "Sort i18n Keys",
            null,
            { sort(file, project) },
            file
        )
    }

    /** Sorts the file in place. Must be called inside a write action. */
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
