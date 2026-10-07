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

    /**
     * Carries [from]'s saved tab and hidden locales over to [to], the same module under a new
     * name or root: the scope is keyed by both, so a change would otherwise lose them and leave
     * the old entries behind in the workspace file.
     *
     * Nothing is done when both resolve to the same scope, nor when [to]'s scope already holds
     * something — that state belongs to another module, which a move must not overwrite.
     */
    fun moveModuleState(from: ModuleConfig, to: ModuleConfig) {
        if (key(TAB, from) == key(TAB, to)) return
        if (properties.isValueSet(key(TAB, to)) || properties.getList(key(HIDDEN_LOCALES, to)) != null) return
        properties.getValue(key(TAB, from))?.let { properties.setValue(key(TAB, to), it) }
        properties.unsetValue(key(TAB, from))
        properties.getList(key(HIDDEN_LOCALES, from))?.let { properties.setList(key(HIDDEN_LOCALES, to), it) }
        properties.setList(key(HIDDEN_LOCALES, from), null)
    }

    companion object {

        private const val PREFIX = "com.ibrahimdans.i18n.toolWindow"
        private const val TAB = "activeTab"
        private const val HIDDEN_LOCALES = "hiddenLocales"
        private val DEFAULT_TAB = ToolWindowTab.TREE

        fun getInstance(project: Project) = ToolWindowViewState(PropertiesComponent.getInstance(project))

        /**
         * The modules of [before] that [after] holds under another scope, paired with their new
         * version.
         *
         * The modules editor replaces a module at its index, appends a new one and shifts the
         * others when one is removed; it keeps no identity across a rename. A module is taken
         * for the same one when it sits at the same index and kept its name or its root
         * directory — a rename keeps the root, a new root keeps the name. A removal shifts a
         * different module to that index, which differs in both and is left alone.
         */
        fun renamedModules(before: List<ModuleConfig>, after: List<ModuleConfig>): List<Pair<ModuleConfig, ModuleConfig>> =
            before.zip(after).filter { (old, new) ->
                (old.name == new.name || old.rootDirectory == new.rootDirectory) &&
                    key(TAB, old) != key(TAB, new)
            }

        /**
         * A module is identified by its name and its root directory: the settings do not forbid
         * two modules of the same name, which shared their tab and hidden locales when the name
         * alone was the scope. An unnamed module is identified by its root, a module without a
         * root by its name. The project scope cannot collide with a module: it carries no
         * `module.` segment.
         */
        private fun key(property: String, module: ModuleConfig?): String {
            if (module == null) return "$PREFIX.$property"
            val root = module.rootDirectory.trim().trim('/')
            val id = when {
                module.name.isBlank() -> module.rootDirectory
                root.isEmpty() -> module.name
                else -> "${module.name}@$root"
            }
            return "$PREFIX.module.$id.$property"
        }
    }
}
