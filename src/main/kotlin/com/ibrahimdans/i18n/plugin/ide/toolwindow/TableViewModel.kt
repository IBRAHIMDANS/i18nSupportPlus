package com.ibrahimdans.i18n.plugin.ide.toolwindow

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
