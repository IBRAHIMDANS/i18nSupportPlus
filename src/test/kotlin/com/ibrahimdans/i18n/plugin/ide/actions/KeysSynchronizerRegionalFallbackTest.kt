package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * *Sync Keys* and regional locales: `en-GB` inherits the keys of `en` (#767), so proposing them
 * would write empty values into `en-GB` that hide the inherited ones.
 */
class KeysSynchronizerRegionalFallbackTest {

    private fun source(locale: String): LocalizationSource =
        mockk<LocalizationSource>(relaxed = true).also {
            every { it.name } returns "common.json"
            every { it.parent } returns locale
            every { it.displayPath } returns "locales/$locale/common.json"
            every { it.locale } returns null
            every { it.namespace } returns null
        }

    private val sources = listOf(source("en"), source("en-GB"), source("fr"))
    private val locales = listOf("en", "en-GB", "fr")
    private val translations = mapOf(
        "actions.save" to mapOf("en" to "Save"),
        "colour" to mapOf("en" to "Color", "en-GB" to "Colour", "fr" to "Couleur"),
        "cart.item_one" to mapOf("en" to "1 item"),
        "cart.item_other" to mapOf("en" to "{{count}} items"),
    )

    private fun missing(config: Config) =
        KeysSynchronizer().findMissingEntries(translations, locales, sources, listOf("common"), config::parentLocale)
            .map { it.key to it.locale }

    @Test
    fun `a key the language holds is not proposed to its regional locale`() {
        assertEquals(
            listOf("actions.save" to "fr", "cart.item_other" to "fr"),
            missing(Config(regionalFallback = true)).sortedBy { it.first },
        )
    }

    @Test
    fun `with regional fallback off, the regional locale receives the keys again`() {
        assertEquals(
            listOf("actions.save" to "en-GB", "actions.save" to "fr", "cart.item_other" to "en-GB", "cart.item_other" to "fr"),
            missing(Config(regionalFallback = false)).sortedWith(compareBy({ it.first }, { it.second })),
        )
    }

    @Test
    fun `a key only the regional locale holds is still proposed to the language`() {
        val gbOnly = mapOf("biscuit" to mapOf("en-GB" to "Biscuit"))
        val missing = KeysSynchronizer().findMissingEntries(gbOnly, locales, sources, listOf("common"), Config()::parentLocale)
        assertEquals(listOf("biscuit" to "en", "biscuit" to "fr"), missing.map { it.key to it.locale })
    }
}
