package com.ibrahimdans.i18n.plugin.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocaleMatchingTest {

    private val project = listOf("de-DE", "en", "en-GB", "en-US", "es-AR", "es-ES", "fr-FR", "ja-JP", "pt-BR", "zh-TW")

    @Test
    fun `the same locale wins, whatever the case and the separator`() {
        assertEquals("en", LocaleMatching.pick("en", project))
        assertEquals("en-GB", LocaleMatching.pick("en_gb", project))
        assertEquals("fr-FR", LocaleMatching.pick("FR-fr", project))
        assertEquals("en", LocaleMatching.pick(" en ", project))
    }

    @Test
    fun `a language alone takes the variant whose region repeats it, then the first alphabetically`() {
        assertEquals("fr-FR", LocaleMatching.pick("fr", project))
        assertEquals("es-ES", LocaleMatching.pick("es", project), "not es-AR, the alphabetical first")
        assertEquals("pt-BR", LocaleMatching.pick("pt", project), "no pt-PT: the alphabetical first")
        assertEquals("zh-TW", LocaleMatching.pick("zh", project))
    }

    @Test
    fun `a regional setting never takes another region, and nothing is nothing`() {
        assertNull(LocaleMatching.pick("en-AU", project))
        assertNull(LocaleMatching.pick("it", project))
        assertNull(LocaleMatching.pick("", project))
        assertNull(LocaleMatching.pick("en", emptyList()))
    }
}
