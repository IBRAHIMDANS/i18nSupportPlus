package com.ibrahimdans.i18n.plugin.ide.preview

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.toolwindow.LocaleStats
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationStatsAnalyzer
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [LocaleProgress] fed by a project's files; its wording is tested in `core`. */
class LocaleProgressProjectTest : PlatformBaseTest() {

    @Test
    fun `the rate is the one of the statistics tab`() {
        addFileToProject("locales/en/common.json", """{"a": "A", "b": "B", "c": "C", "d": "D"}""")
        addFileToProject("locales/fr/common.json", """{"a": "A", "b": "B", "c": ""}""")
        val coverage = ReadAction.compute<List<LocaleStats>, RuntimeException> { TranslationStatsAnalyzer.analyze(project) }
        assertEquals("i18n: fr · 50 %", LocaleProgress.text("fr", coverage))
    }
}
