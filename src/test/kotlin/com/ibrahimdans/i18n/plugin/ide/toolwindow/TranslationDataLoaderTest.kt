package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.Localization
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TranslationDataLoaderTest {

    // Minimal helper — tree and localization are irrelevant for these pure extraction tests
    @Suppress("UNCHECKED_CAST")
    private fun source(name: String, parent: String) = LocalizationSource(
        tree = null,
        name = name,
        parent = parent,
        displayPath = "$parent/$name",
        localization = mockk<Localization<*>>() as Localization<com.intellij.psi.PsiElement>
    )

    // ---- extractLocale ----

    @Test
    fun `extractLocale uses parent dir when it looks like a locale`() {
        assertEquals("en", TranslationDataLoader.extractLocale(source("common.json", "en")))
        assertEquals("fr", TranslationDataLoader.extractLocale(source("translation.json", "fr")))
        assertEquals("zh", TranslationDataLoader.extractLocale(source("app.json", "zh")))
    }

    @Test
    fun `extractLocale uses parent dir for locale with region`() {
        assertEquals("en-US", TranslationDataLoader.extractLocale(source("common.json", "en-US")))
        assertEquals("pt-BR", TranslationDataLoader.extractLocale(source("auth.json", "pt-BR")))
        assertEquals("zh_CN", TranslationDataLoader.extractLocale(source("app.yaml", "zh_CN")))
    }

    @Test
    fun `extractLocale falls back to stem when parent is not a locale`() {
        // flat file: locales/en.json → stem "en"
        assertEquals("en", TranslationDataLoader.extractLocale(source("en.json", "locales")))
        assertEquals("fr", TranslationDataLoader.extractLocale(source("fr.yaml", "public")))
    }

    @Test
    fun `extractLocale does not treat long parent names as locale`() {
        // "locales" is 7 chars → not a locale, falls back to stem
        assertEquals("translation", TranslationDataLoader.extractLocale(source("translation.json", "locales")))
    }

    // ---- extractNamespace ----

    @Test
    fun `extractNamespace returns filename stem`() {
        assertEquals("common", TranslationDataLoader.extractNamespace(source("common.json", "en")))
        assertEquals("auth", TranslationDataLoader.extractNamespace(source("auth.yaml", "fr")))
        assertEquals("translation", TranslationDataLoader.extractNamespace(source("translation.json", "en")))
    }

    @Test
    fun `extractNamespace for locale-named flat file returns the locale as namespace`() {
        // en.json in a non-locale parent: namespace and locale both = "en"
        assertEquals("en", TranslationDataLoader.extractNamespace(source("en.json", "locales")))
    }
}
