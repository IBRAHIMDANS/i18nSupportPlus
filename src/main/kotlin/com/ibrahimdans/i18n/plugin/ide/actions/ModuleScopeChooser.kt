package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * The module a project-wide action operates on. [config] is null when the project
 * has no module configuration, meaning "the whole project".
 */
internal data class ModuleScope(val config: ModuleConfig?)

/**
 * Asks which module a CSV export/import should operate on.
 *
 * A CSV row is keyed by `ns:key` alone — it carries no module column — so a file
 * spanning several modules cannot round-trip: two modules owning the same namespace
 * and locale would collapse onto the same row (export silently keeping one value,
 * import writing back into whichever file resolves first). Rather than lose data
 * quietly, an explicit module is required as soon as several are configured.
 *
 * Returns null when the user cancels. Returns `ModuleScope(null)` (project-wide)
 * when fewer than two modules are configured, which is the pre-existing behaviour.
 */
internal fun chooseModuleScope(project: Project, title: String): ModuleScope? {
    val modules = Settings.getInstance(project).modules.filter { it.rootDirectory.isNotBlank() }
    if (modules.size < 2) return ModuleScope(null)

    val names: Array<String> = modules.map { it.name.ifBlank { it.rootDirectory } }.toTypedArray()
    val index = Messages.showDialog(
        project,
        "A CSV file has no module column, so it cannot span several modules.\nChoose the module to work on:",
        title,
        names,
        0,
        null
    )
    if (index < 0) return null

    val selected: ModuleConfig = modules.getOrNull(index) ?: return null
    return ModuleScope(selected)
}
