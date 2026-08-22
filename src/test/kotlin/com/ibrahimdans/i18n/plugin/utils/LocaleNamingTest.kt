package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.Localization
import com.ibrahimdans.i18n.LocalizationSource
import com.intellij.psi.PsiElement
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The single locale-naming rule the gutter tooltip, the hover popup and the tool window's
 * data loader now share. Each used to hold a private copy of the shape-only regex #122
 * replaced, and the copies disagreed with each other on the order they tried the file stem
 * and the parent directory in.
 */
class LocaleNamingTest {

    @Suppress("UNCHECKED_CAST")
    private fun source(name: String, parent: String) = LocalizationSource(
        tree = null,
        name = name,
        parent = parent,
        displayPath = "$parent/$name",
        localization = mockk<Localization<*>>() as Localization<PsiElement>
    )

    // ---- localeLabel ----

    @Test
    fun `takes the parent directory when the stem is not a locale`() {
        assertEquals("en", source("common.json", "en").localeLabel())
        assertEquals("pt-BR", source("auth.json", "pt-BR").localeLabel())
        assertEquals("zh_CN", source("app.yaml", "zh_CN").localeLabel())
    }

    @Test
    fun `takes the file stem when it is a locale`() {
        assertEquals("en", source("en.json", "locales").localeLabel())
        assertEquals("fr", source("fr.yaml", "public").localeLabel())
        assertEquals("pt-BR", source("pt-BR.json", "locales").localeLabel())
    }

    @Test
    fun `prefers the stem when both look like a locale`() {
        // The file's own name is the more specific designation of the two.
        assertEquals("fr", source("fr.json", "en").localeLabel())
    }

    @Test
    fun `falls back to the stem when neither part is a locale`() {
        // The regression #122 fixed elsewhere but left alive here: "api" is a source folder,
        // not a language, so this file used to be loaded under the locale "api".
        assertEquals("common", source("common.json", "api").localeLabel())
        assertEquals("common", source("common.json", "web").localeLabel())
        assertEquals("translation", source("translation.json", "locales").localeLabel())
    }

    @Test
    fun `does not mistake a source folder for a locale`() {
        listOf("api", "web", "ios", "src", "lib", "app", "bin").forEach { dir ->
            assertEquals(
                "messages",
                source("messages.json", dir).localeLabel(),
                "expected '$dir' not to be read as a locale"
            )
        }
    }

    // ---- isLocaleNamedFile ----

    @Test
    fun `a file named after its locale holds no namespace`() {
        assertTrue(source("en.json", "locales").isLocaleNamedFile())
        assertTrue(source("pt-BR.json", "locales").isLocaleNamedFile())
    }

    @Test
    fun `a file named after a namespace does`() {
        assertFalse(source("common.json", "en").isLocaleNamedFile())
        assertFalse(source("translation.json", "en").isLocaleNamedFile())
        assertFalse(source("messages.json", "api").isLocaleNamedFile())
    }
}
