package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService.Companion.looksLikeLocale
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for the locale-detection heuristic used when no translations
 * root is configured. The old shape-only regex accepted any 2-3 letter word,
 * so monorepo directories like web/ or ios/ were scanned as locales and their
 * package.json / .prettierrc content shown as translations.
 */
class LooksLikeLocaleTest {

    @Test
    fun acceptsIsoLanguageCodes() {
        listOf("en", "fr", "de", "pt", "zh", "EN", "Fr").forEach {
            assertTrue(looksLikeLocale(it), "expected '$it' to be a locale")
        }
    }

    @Test
    fun acceptsThreeLetterIso6392Codes() {
        listOf("fil", "haw", "yue").forEach {
            assertTrue(looksLikeLocale(it), "expected '$it' to be a locale")
        }
    }

    @Test
    fun acceptsLanguageWithRegion() {
        listOf("pt-BR", "pt_BR", "zh_CN", "en-US", "en_gb").forEach {
            assertTrue(looksLikeLocale(it), "expected '$it' to be a locale")
        }
    }

    @Test
    fun acceptsLanguageWithScript() {
        listOf("sr-Latn", "zh_Hans").forEach {
            assertTrue(looksLikeLocale(it), "expected '$it' to be a locale")
        }
    }

    @Test
    fun rejectsCommonDirectoryNames() {
        listOf("web", "ios", "api", "src", "lib", "app", "bin", "doc", "out").forEach {
            assertFalse(looksLikeLocale(it), "expected '$it' NOT to be a locale")
        }
    }

    @Test
    fun rejectsUnknownRegionAndMalformedTags() {
        listOf("en-XX", "fr_QQ", "xx", "xx-YY", "en-US-extra", "", "e", "1n").forEach {
            assertFalse(looksLikeLocale(it), "expected '$it' NOT to be a locale")
        }
    }
}
