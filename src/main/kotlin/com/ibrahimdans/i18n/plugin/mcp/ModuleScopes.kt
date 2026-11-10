package com.ibrahimdans.i18n.plugin.mcp

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.mcp.TranslationAgentTools.Refused
import com.intellij.openapi.project.Project

/**
 * The translation modules an agent names, the same for every tool: by name, as *Fill Missing
 * Translations* offers them, or one unnamed scope, [TranslationAgentTools.PROJECT], when the project
 * configures fewer than two.
 */
internal class ModuleScopes(private val project: Project) {

    fun all(): List<Pair<String, ModuleConfig?>> {
        val modules = Settings.getInstance(project).config().modules.filter { it.rootDirectory.isNotBlank() }
        if (modules.size < 2) return listOf(TranslationAgentTools.PROJECT to null)
        return modules.map { it.name.ifBlank { it.rootDirectory } to it }
    }

    /** The module named [name]; the only one when [name] is blank. Refused when it names none, or several are left to choose from. */
    fun named(name: String?): Pair<String, ModuleConfig?> {
        val scopes = all()
        if (name.isNullOrBlank()) {
            return scopes.singleOrNull() ?: throw Refused("Several modules: name one of ${scopes.joinToString { it.first }}")
        }
        return scopes.firstOrNull { it.first == name }
            ?: throw Refused("Unknown module '$name'. Modules: ${scopes.joinToString { it.first }}")
    }
}
