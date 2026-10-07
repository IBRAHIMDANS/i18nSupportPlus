package com.ibrahimdans.i18n.plugin.ide.preview

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.toolwindow.LocaleStats
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationStatsAnalyzer
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocaleProgressTest : PlatformBaseTest() {

    private fun stats(locale: String, translated: Int, total: Int) =
        LocaleStats(locale, total, translated, total - translated, translated * 100.0 / total)

    @Test
    fun `the text appends the rate of the preview locale`() {
        val coverage = listOf(stats("en", 50, 50), stats("fr", 47, 50))
        assertEquals("i18n: fr · 94 %", LocaleProgress.text("fr", coverage))
    }

    @Test
    fun `a language finds its regional locale`() {
        assertEquals("i18n: en · 100 %", LocaleProgress.text("en", listOf(stats("en-GB", 10, 10))))
    }

    @Test
    fun `without stats for the locale, the locale alone is shown`() {
        assertEquals("i18n: fr", LocaleProgress.text("fr", emptyList()))
        assertEquals("i18n: ja", LocaleProgress.text("ja", listOf(stats("en", 10, 10))))
    }

    /** One key missing out of a thousand is not complete: the rate is rounded down. */
    @Test
    fun `an almost complete locale never reads 100`() {
        assertEquals(99, LocaleProgress.percentOf(stats("fr", 999, 1000)))
    }

    @Test
    fun `the tooltip lists every locale with its rate`() {
        val tooltip = LocaleProgress.tooltip(listOf(stats("en", 4, 4), stats("fr", 3, 4)))
        assertTrue(tooltip.startsWith("<html>"), tooltip)
        assertTrue(tooltip.contains("en: 100 % (4/4 keys)"), tooltip)
        assertTrue(tooltip.contains("fr: 75 % (3/4 keys)"), tooltip)
    }

    @Test
    fun `without stats, the tooltip keeps its description only`() {
        assertFalse(LocaleProgress.tooltip(emptyList()).contains("<br/>"))
    }

    @Test
    fun `the rate is the one of the statistics tab`() {
        addFileToProject("locales/en/common.json", """{"a": "A", "b": "B", "c": "C", "d": "D"}""")
        addFileToProject("locales/fr/common.json", """{"a": "A", "b": "B", "c": ""}""")
        val coverage = ReadAction.compute<List<LocaleStats>, RuntimeException> { TranslationStatsAnalyzer.analyze(project) }
        assertEquals("i18n: fr · 50 %", LocaleProgress.text("fr", coverage))
    }
}
