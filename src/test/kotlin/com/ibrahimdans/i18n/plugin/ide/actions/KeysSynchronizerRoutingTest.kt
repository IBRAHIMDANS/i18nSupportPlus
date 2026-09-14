package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which file *Sync Keys* proposes to write a missing key to.
 *
 * A key spelled without a namespace prefix was routed to the first file of the target locale,
 * whatever its namespace: with `common` as the default namespace, `actions.save` was proposed
 * for `fr/auth.json` — the first name in alphabetical order — and applying would have created
 * it there.
 */
class KeysSynchronizerRoutingTest {

    /** [parent] is the file's directory name alone, which is what the locale is read from. */
    private fun source(locale: String, name: String, parent: String = locale): LocalizationSource =
        mockk<LocalizationSource>(relaxed = true).also {
            every { it.name } returns name
            every { it.parent } returns parent
            every { it.displayPath } returns "locales/$parent/$name"
            // A relaxed mock answers "" for these, which reads as a locale a template stated.
            every { it.locale } returns null
            every { it.namespace } returns null
        }

    private val perNamespace = listOf(
        source("en", "auth.json"), source("en", "common.json"),
        source("fr", "auth.json"), source("fr", "common.json"),
    )

    @Test
    fun `a key without a prefix goes to the default namespace's file, not to the first file of the locale`() {
        val translations = mapOf(
            "actions.save" to mapOf("en" to "Save"),
            "auth:login" to mapOf("en" to "Log in"),
        )

        val missing = KeysSynchronizer().findMissingEntries(translations, listOf("en", "fr"), perNamespace, listOf("common"))

        assertEquals(
            listOf("actions.save" to "locales/fr/common.json", "auth:login" to "locales/fr/auth.json"),
            missing.map { it.key to it.source.displayPath },
        )
    }

    @Test
    fun `a key of no default namespace file is skipped rather than written elsewhere`() {
        val translations = mapOf("actions.save" to mapOf("en" to "Save"))

        val missing = KeysSynchronizer().findMissingEntries(translations, listOf("en", "fr"), perNamespace, listOf("translation"))

        assertEquals(emptyList<String>(), missing.map { it.key }, "no fr/translation.json exists, and auth.json is not it")
    }

    @Test
    fun `one file per locale takes the key whatever it is named`() {
        val perLocale = listOf(source("en", "en.json", "locales"), source("fr", "fr.json", "locales"))
        val translations = mapOf("actions.save" to mapOf("en" to "Save"))

        val missing = KeysSynchronizer().findMissingEntries(translations, listOf("en", "fr"), perLocale, listOf("translation"))

        assertEquals(listOf("locales/locales/fr.json"), missing.map { it.source.displayPath })
    }
}
