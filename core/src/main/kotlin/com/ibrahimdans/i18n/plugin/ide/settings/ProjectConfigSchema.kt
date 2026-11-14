package com.ibrahimdans.i18n.plugin.ide.settings

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * The JSON schema of `i18n-check.json`, derived from [ProjectConfigFile]: every setting it takes, a
 * flag as a boolean, a setting with fixed values as their list, and the fields of a module. A name
 * the file refuses is refused here too (`additionalProperties: false`), so the editor flags a typo
 * before [ProjectConfigFile.parse] does.
 */
object ProjectConfigSchema {

    /** The schema, as the text of a JSON document. */
    fun text(): String {
        val root = JsonObject().apply {
            addProperty("\$schema", "http://json-schema.org/draft-07/schema#")
            addProperty("title", "i18n Support Plus project settings")
            addProperty("description", "Team settings of i18n Support Plus, read by the IDE and by i18n-check.")
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                ProjectConfigFile.shapes.forEach { (name, shape) -> add(name, valueOf(shape)) }
                add(ProjectConfigFile.MODULES, modules())
            })
            addProperty("additionalProperties", false)
        }
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n"
    }

    private fun modules(): JsonObject = JsonObject().apply {
        addProperty("type", "array")
        add("items", JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                ProjectConfigFile.moduleShapes.forEach { (name, shape) ->
                    add(name, if (name == ProjectConfigFile.CODE_ROOTS) codeRoots() else valueOf(shape))
                }
            })
            addProperty("additionalProperties", false)
        })
    }

    /** A comma-separated string of folders, or an array of them. */
    private fun codeRoots(): JsonObject = JsonObject().apply {
        add("anyOf", JsonArray().apply {
            add(type("string"))
            add(type("array").apply { add("items", type("string")) })
        })
    }

    private fun valueOf(shape: ProjectConfigFile.Shape): JsonObject {
        val choices = shape.choices
        return when {
            shape.flag -> type("boolean")
            choices != null -> type("string").apply {
                add("enum", JsonArray().apply { choices.sorted().forEach { add(it) } })
            }
            else -> type("string")
        }
    }

    private fun type(name: String): JsonObject = JsonObject().apply { addProperty("type", name) }
}
