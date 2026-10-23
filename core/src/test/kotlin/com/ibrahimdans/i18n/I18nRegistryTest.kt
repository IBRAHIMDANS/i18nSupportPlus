package com.ibrahimdans.i18n

import com.ibrahimdans.i18n.extensions.technology.lingui.LinguiTechnology
import com.ibrahimdans.i18n.extensions.technology.vuei18n.VueI18nTechnology
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A registry held in memory hands back what it was given, typed as the technologies it holds. */
class I18nRegistryTest {

    private val format = object : TranslationFormat {
        override fun config() = LocalizationConfigImpl("test")
    }

    @Test
    fun `lists the formats and technologies it holds, in order`() {
        val lingui = LinguiTechnology()
        val vue = VueI18nTechnology()
        val registry: I18nRegistry<String, TranslationFormat> = StaticI18nRegistry(listOf(format), listOf(lingui, vue))

        assertEquals(listOf(format), registry.formats())
        assertEquals(listOf(lingui, vue), registry.technologies())
    }

    @Test
    fun `holds nothing by default`() {
        val registry = StaticI18nRegistry<Nothing, Nothing>()

        assertTrue(registry.formats().isEmpty())
        assertTrue(registry.technologies().isEmpty())
    }
}
