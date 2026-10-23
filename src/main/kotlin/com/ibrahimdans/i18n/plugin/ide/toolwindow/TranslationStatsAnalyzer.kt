package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.openapi.project.Project

/**
 * Analyzes translation coverage per locale of a project: [TranslationDataLoader] reads the flat
 * key -> (locale -> value) map, [TranslationCoverage] computes the statistics from it.
 */
object TranslationStatsAnalyzer {

    /**
     * Analyzes coverage for each locale found in the project (or in a specific module).
     * Returns a list of [LocaleStats] sorted by locale name.
     */
    fun analyze(project: Project, moduleConfig: ModuleConfig? = null): List<LocaleStats> =
        TranslationCoverage.analyze(TranslationDataLoader.loadAllTranslations(project, moduleConfig))

    /** The coverage report of the project (or of a specific module), see [CoverageReport]. */
    fun report(project: Project, moduleConfig: ModuleConfig? = null): CoverageReport =
        TranslationCoverage.report(TranslationDataLoader.loadAllTranslations(project, moduleConfig))
}
