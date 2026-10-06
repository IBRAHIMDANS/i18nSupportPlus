package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

/**
 * The tool window's tabs, in the order [ShellContent] adds them: the ordinal is the tab index.
 *
 * Persisted by name rather than by index, so a tab inserted later does not silently shift what
 * an existing workspace restores.
 */
internal enum class ToolWindowTab { TREE, TABLE, STATS }

/**
 * What the tool window looked like when it was last used: the selected tab and the locale
 * columns hidden from the table.
 *
 * Both were plain fields of the panels, so every project reopening started from the tree with
 * every locale shown — and hiding locales is precisely what a project with six or more of them
 * needs, every single time. They are view state, not configuration: they live in the project's
 * [PropertiesComponent] (the workspace file), not in [com.ibrahimdans.i18n.plugin.ide.settings.Settings],
 * which is shared through the VCS.
 *
 * Every value is scoped: `module == null` is the project-wide scope — the single-module shell,
 * or a value the shell shares between modules — and a module gets a scope of its own, so
 * hiding `de` in the backend does not hide it in the frontend.
 *
 * Writing the default value removes the entry instead of storing it, so the workspace file only
 * carries what the user actually changed.
 */
internal class ToolWindowViewState(private val properties: PropertiesComponent) {

    /** The tab to show; [ToolWindowTab.TREE] — the tool window's historic default — when none was saved. */
    fun activeTab(module: ModuleConfig?): ToolWindowTab {
        val saved = properties.getValue(key(TAB, module)) ?: return DEFAULT_TAB
        // A name from a newer or older build that no longer exists falls back to the default.
        return ToolWindowTab.entries.firstOrNull { it.name == saved } ?: DEFAULT_TAB
    }

    fun setActiveTab(module: ModuleConfig?, tab: ToolWindowTab) {
        properties.setValue(key(TAB, module), tab.name, DEFAULT_TAB.name)
    }

    /** The locales hidden from the table; none when nothing was saved. */
    fun hiddenLocales(module: ModuleConfig?): Set<String> =
        properties.getList(key(HIDDEN_LOCALES, module)).orEmpty().toSet()

    /**
     * Stored sorted, so the same set always writes the same workspace entry. An empty set is
     * written as `null`: lists are kept apart from plain values, and `unsetValue` does not
     * reach them.
     */
    fun setHiddenLocales(module: ModuleConfig?, locales: Set<String>) {
        properties.setList(key(HIDDEN_LOCALES, module), locales.sorted().ifEmpty { null })
    }

    companion object {

        private const val PREFIX = "com.ibrahimdans.i18n.toolWindow"
        private const val TAB = "activeTab"
        private const val HIDDEN_LOCALES = "hiddenLocales"
        private val DEFAULT_TAB = ToolWindowTab.TREE

        fun getInstance(project: Project) = ToolWindowViewState(PropertiesComponent.getInstance(project))

        /**
         * A module is identified by its name, as in the selector; an unnamed one by its root, so
         * two unnamed modules do not share a scope. The project scope cannot collide with a
         * module: it carries no `module.` segment.
         */
        private fun key(property: String, module: ModuleConfig?): String {
            if (module == null) return "$PREFIX.$property"
            val id = module.name.ifBlank { module.rootDirectory }
            return "$PREFIX.module.$id.$property"
        }
    }
}
