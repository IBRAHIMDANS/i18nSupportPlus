package com.ibrahimdans.i18n.plugin.ide.preview

import com.ibrahimdans.i18n.plugin.ide.toolwindow.LocaleStats
import com.ibrahimdans.i18n.plugin.utils.CoreMessages
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

class LocaleProgressTest {

    @BeforeEach
    fun setUp() {
        // The wording is asserted in English.
        CoreMessages.locale = { Locale.ENGLISH }
    }

    @AfterEach
    fun tearDown() {
        CoreMessages.locale = Locale::getDefault
    }

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

}
