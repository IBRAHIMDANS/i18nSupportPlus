package com.ibrahimdans.i18n.plugin.check

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationCoverage
import com.ibrahimdans.i18n.plugin.utils.LocaleParent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocaleCheckRegionalTest {

    private val partial = mapOf(
        "color" to mapOf("en" to "color", "en-GB" to "colour", "pt-BR" to "cor"),
        "save" to mapOf("en" to "Save", "pt-BR" to "Salvar"),
        "title" to mapOf("en" to "Title"),
    )

    private fun problems(translations: Map<String, Map<String, String>>, parent: (String) -> String? = LocaleParent::of) =
        LocaleCheck.run(translations, "en", parentLocale = parent).associateBy { it.locale }

    @Test
    fun `the language of a locale`() {
        assertEquals("en", LocaleParent.of("en-GB"))
        assertEquals("pt", LocaleParent.of("pt_BR"))
        assertEquals("zh", LocaleParent.of("zh-Hant-TW"))
        assertNull(LocaleParent.of("en"))
    }

    @Test
    fun `a key its language holds is not missing from a regional locale`() {
        assertEquals(emptyList<String>(), problems(partial).getValue("en-GB").missing)
    }

    @Test
    fun `a regional locale without its language is compared in full`() {
        assertEquals(listOf("title"), problems(partial).getValue("pt-BR").missing)
    }

    @Test
    fun `with the option off a regional locale is compared in full`() {
        val off = Config(regionalFallback = false)
        assertEquals(listOf("save", "title"), problems(partial, off::parentLocale).getValue("en-GB").missing)
    }

    @Test
    fun `an orphan or empty key of a regional locale stays reported`() {
        val found = problems(
            mapOf(
                "title" to mapOf("en" to "Title", "en-GB" to " "),
                "lorry" to mapOf("en-GB" to "Lorry"),
            ),
        ).getValue("en-GB")

        assertEquals(listOf("lorry"), found.orphans)
        assertEquals(listOf("title"), found.empty)
    }

    @Test
    fun `a plural group its language holds is not missing`() {
        val found = problems(mapOf("item_one" to mapOf("en" to "item"), "item_other" to mapOf("en" to "items"), "x" to mapOf("en-GB" to "x", "en" to "x")))
        assertEquals(emptyList<String>(), found.getValue("en-GB").missing)
    }

    @Test
    fun `an alias inherits from the language of its target`() {
        val config = Config(localeAliases = "gb=en-GB")
        assertEquals("en", config.parentLocale("gb"))
        assertNull(Config(regionalFallback = false).parentLocale("en-GB"))
    }

    @Test
    fun `coverage counts an inherited key as covered, and a blank value of the language as missing`() {
        val stats = TranslationCoverage.analyze(
            mapOf(
                "color" to mapOf("en" to "color", "en-GB" to "colour"),
                "save" to mapOf("en" to "Save"),
                "blank" to mapOf("en" to " "),
                "empty" to mapOf("en" to "Empty", "en-GB" to ""),
            ),
            LocaleParent::of,
        ).first { it.locale == "en-GB" }

        assertEquals(2, stats.translated)
        assertEquals(1, stats.inherited)
        assertEquals(listOf("blank"), stats.missingKeys)
        assertEquals(listOf("empty"), stats.emptyKeys)
    }

    @Test
    fun `coverage without a parent is unchanged`() {
        val stats = TranslationCoverage.analyze(partial).first { it.locale == "en-GB" }

        assertEquals(1, stats.translated)
        assertEquals(0, stats.inherited)
        assertEquals(listOf("save", "title"), stats.missingKeys)
    }
}
