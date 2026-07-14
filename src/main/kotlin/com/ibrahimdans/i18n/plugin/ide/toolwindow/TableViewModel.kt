package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.references.translation.ReferencesAccumulator
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.openapi.project.Project
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext

/**
 * Represents a single row in the translation table.
 *
 * @param usageCount Number of usages found in source code.
 *   -1 = not yet scanned, 0 = orphan (unused), ≥1 = used.
 */
data class TranslationRow(val key: String, val values: Map<String, String>, val usageCount: Int = -1)

/**
 * View model for the table-based translation view.
 * Provides a flat list of all translation keys with their locale values.
 */
class TableViewModel {

    /**
     * Loads all translations as a flat list of rows.
     * When [moduleConfig] is non-null, only translations from that module are loaded.
     */
    fun loadRows(project: Project, moduleConfig: ModuleConfig? = null): List<TranslationRow> {
        val data = TranslationDataLoader.loadAllTranslations(project, moduleConfig)
        return data.entries
            .sortedBy { it.key }
            .map { (key, localeValues) -> TranslationRow(key, localeValues) }
    }

    /**
     * Returns all discovered locales for the project (or for the given module).
     */
    fun getLocales(project: Project, moduleConfig: ModuleConfig? = null): List<String> {
        return TranslationDataLoader.discoverLocales(project, moduleConfig)
    }

    /**
     * Returns rows whose key or any translation value contains [query] (case-insensitive).
     * Returns the original list unchanged when [query] is blank.
     */
    fun filter(query: String, rows: List<TranslationRow>): List<TranslationRow> {
        if (query.isBlank()) return rows
        val lowerQuery = query.lowercase()
        return rows.filter { row ->
            row.key.lowercase().contains(lowerQuery) ||
                row.values.values.any { it.lowercase().contains(lowerQuery) }
        }
    }

    /**
     * Writes [value] for [key] in [locale], routing to the right translation file
     * by namespace and locale. Creates the entry when the locale does not have it yet.
     *
     * [moduleConfig] must be the one the displayed rows were loaded with: without it,
     * two modules owning a file with the same namespace and locale (the normal case in
     * a monorepo) would resolve to whichever comes first project-wide, and an edit made
     * in one module's table would land in the other module's file.
     *
     * Returns false when no matching translation file exists or the write fails
     * (e.g. read-only file) — the caller must then restore the previous cell value.
     * Must be called on the EDT.
     */
    fun saveValue(
        project: Project,
        key: String,
        locale: String,
        value: String,
        moduleConfig: ModuleConfig? = null,
    ): Boolean {
        val colonIdx = key.indexOf(':')
        val namespace = if (colonIdx > 0) key.substring(0, colonIdx) else null
        val source = TranslationDataLoader.findSources(project, moduleConfig)
            .firstOrNull { source ->
                TranslationDataLoader.extractLocale(source) == locale &&
                    (namespace == null || TranslationDataLoader.extractNamespace(source) == namespace)
            } ?: return false
        return try {
            DialogViewModel(project).saveTranslation(source, KeysSynchronizer().buildFullKey(key), value)
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * Counts usages of each key in the project's source files using PsiSearchHelper.
     * Returns a new list of rows with [TranslationRow.usageCount] filled in.
     *
     * This method performs PSI reads and must be called from a background thread.
     */
    fun countUsages(project: Project, rows: List<TranslationRow>): List<TranslationRow> {
        val config = Settings.getInstance(project).config()
        val searchScope = config.searchScope(project)
        val searchHelper = PsiSearchHelper.getInstance(project)

        return rows.map { row ->
            // Search with the full key (namespace:key.path) so that
            // "navigation:menu.profile" matches t('navigation:menu.profile') in source.
            // Also search with the bare key (without namespace) for cases where the
            // namespace is implicit (e.g. useTranslation('navigation') + t('menu.profile')).
            val colonIdx = row.key.indexOf(':')
            val bareKey = if (colonIdx > 0) row.key.substring(colonIdx + 1) else row.key

            val accumulator = ReferencesAccumulator(bareKey)

            // Search full key (with namespace prefix) first
            if (colonIdx > 0) {
                searchHelper.processElementsWithWord(
                    accumulator.process(),
                    searchScope,
                    row.key,
                    UsageSearchContext.ANY,
                    true
                )
            }

            // Also search bare key for implicit namespace usage
            searchHelper.processElementsWithWord(
                accumulator.process(),
                searchScope,
                bareKey,
                UsageSearchContext.ANY,
                true
            )
            row.copy(usageCount = accumulator.entries().size)
        }
    }
}
