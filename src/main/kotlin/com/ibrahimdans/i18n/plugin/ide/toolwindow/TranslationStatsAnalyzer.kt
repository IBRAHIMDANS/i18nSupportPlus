package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.openapi.project.Project

/**
 * Statistics for a single locale.
 * [total] is the union of all keys across all locales.
 * [translated] is the number of non-empty values for this locale.
 * [missingKeys] is the list of key names absent or blank for this locale.
 */
data class LocaleStats(
    val locale: String,
    val total: Int,
    val translated: Int,
    val missing: Int,
    val percent: Double,
    val missingKeys: List<String> = emptyList()
)

/**
 * Analyzes translation coverage per locale.
 * Uses [TranslationDataLoader] to get the flat key -> (locale -> value) map,
 * then computes per-locale statistics.
 */
object TranslationStatsAnalyzer {

    /**
     * Analyzes coverage for each locale found in the project (or in a specific module).
     * Returns a list of [LocaleStats] sorted by locale name.
     */
    fun analyze(project: Project, moduleConfig: ModuleConfig? = null): List<LocaleStats> {
        val allTranslations = TranslationDataLoader.loadAllTranslations(project, moduleConfig)
        val totalKeys = allTranslations.size
        if (totalKeys == 0) return emptyList()

        // Collect all locales
        val locales = allTranslations.values
            .flatMap { it.keys }
            .distinct()
            .sorted()

        return locales.map { locale ->
            val missingKeys = allTranslations.entries
                .filter { (_, localeMap) -> localeMap[locale].isNullOrBlank() }
                .map { (key, _) -> key }
                .sorted()
            val translated = totalKeys - missingKeys.size
            val percent = if (totalKeys > 0) translated.toDouble() / totalKeys * 100.0 else 0.0
            LocaleStats(
                locale = locale,
                total = totalKeys,
                translated = translated,
                missing = missingKeys.size,
                percent = percent,
                missingKeys = missingKeys
            )
        }
    }
}
