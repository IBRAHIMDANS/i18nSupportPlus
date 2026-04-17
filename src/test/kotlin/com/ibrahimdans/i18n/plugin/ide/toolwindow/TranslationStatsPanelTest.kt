package com.ibrahimdans.i18n.plugin.ide.toolwindow

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TranslationStatsPanelTest {

    // ---- parseTranslationKey ----

    @Test
    fun `parseTranslationKey with namespace splits correctly`() {
        val (ns, segments) = parseTranslationKey("common:menu.home")
        assertEquals("common", ns)
        assertEquals(listOf("menu", "home"), segments)
    }

    @Test
    fun `parseTranslationKey without namespace returns null ns`() {
        val (ns, segments) = parseTranslationKey("menu.home")
        assertNull(ns)
        assertEquals(listOf("menu", "home"), segments)
    }

    @Test
    fun `parseTranslationKey with deep path`() {
        val (ns, segments) = parseTranslationKey("auth:forms.login.errors.required")
        assertEquals("auth", ns)
        assertEquals(listOf("forms", "login", "errors", "required"), segments)
    }

    @Test
    fun `parseTranslationKey with flat key (no dot)`() {
        val (ns, segments) = parseTranslationKey("greeting")
        assertNull(ns)
        assertEquals(listOf("greeting"), segments)
    }

    @Test
    fun `parseTranslationKey with namespace and flat key`() {
        val (ns, segments) = parseTranslationKey("common:greeting")
        assertEquals("common", ns)
        assertEquals(listOf("greeting"), segments)
    }

    // ---- selectReferenceLocale ----

    @Test
    fun `selectReferenceLocale returns locale with most translated keys`() {
        val stats = listOf(
            LocaleStats("fr", 10, 6, 4, 60.0),
            LocaleStats("en", 10, 10, 0, 100.0),
            LocaleStats("de", 10, 3, 7, 30.0)
        )
        assertEquals("en", selectReferenceLocale(stats))
    }

    @Test
    fun `selectReferenceLocale returns null for empty list`() {
        assertNull(selectReferenceLocale(emptyList()))
    }

    @Test
    fun `selectReferenceLocale returns single element`() {
        val stats = listOf(LocaleStats("en", 5, 5, 0, 100.0))
        assertEquals("en", selectReferenceLocale(stats))
    }

    @Test
    fun `selectReferenceLocale handles all locales missing all keys`() {
        val stats = listOf(
            LocaleStats("en", 3, 0, 3, 0.0),
            LocaleStats("fr", 3, 0, 3, 0.0)
        )
        // maxByOrNull returns the first when all equal — deterministic
        assertNotNull(selectReferenceLocale(stats))
    }
}
