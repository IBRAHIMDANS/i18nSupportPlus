package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewHash
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewState
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
    fun `selectReferenceLocale prefers the locale the module declares`() {
        val stats = listOf(LocaleStats("fr-FR", 10, 6, 4, 60.0), LocaleStats("en", 10, 10, 0, 100.0))
        assertEquals("fr-FR", selectReferenceLocale(stats, ModuleConfig(name = "web", referenceLocale = "fr")))
        assertEquals("en", selectReferenceLocale(stats, ModuleConfig(name = "web", referenceLocale = "ja")))
    }

    @Test
    fun `selectReferenceLocale takes the reference of the lone configured module`() {
        val stats = listOf(LocaleStats("de", 10, 6, 4, 60.0), LocaleStats("en", 10, 10, 0, 100.0))
        val lone = ModuleConfig(name = "web", rootDirectory = "web", referenceLocale = "de")
        assertEquals("de", selectReferenceLocale(stats, modules = listOf(lone)))
    }

    @Test
    fun `selectReferenceLocale ignores module references when several modules are configured`() {
        val stats = listOf(LocaleStats("de", 10, 6, 4, 60.0), LocaleStats("en", 10, 10, 0, 100.0))
        val modules = listOf(
            ModuleConfig(name = "web", rootDirectory = "web", referenceLocale = "de"),
            ModuleConfig(name = "api", rootDirectory = "api", referenceLocale = "de"),
        )
        assertEquals("en", selectReferenceLocale(stats, modules = modules))
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

    // ---- reviewCounts ----

    private fun review(state: ReviewState, value: String, source: String? = null) =
        Review(state, ReviewHash.of(value), "Alice", "2026-10-11T14:02:11Z").let { r ->
            if (source == null) r else r.copy(source = ReviewSource.of("en", source))
        }

    /** Two namespaces, so the report has a total row above them; English is the reference. */
    private val report = TranslationCoverage.report(
        mapOf(
            "menu.home" to mapOf("en" to "Home page", "fr" to "Accueil", "de" to "Start"),
            "menu.about" to mapOf("en" to "About", "fr" to "À propos", "de" to "Über"),
            "common:cart.title" to mapOf("en" to "Cart", "fr" to "Panier", "de" to "Warenkorb"),
        )
    )

    /**
     * fr: `menu.home` to review, `menu.about` approved on its value, `common:cart.title` approved on
     * an older value. de: `menu.home` approved on an older source, `menu.about` approved as it reads.
     */
    private val reviews = TableReviews(
        mapOf(
            "fr" to mapOf(
                ReviewKey("translation", "menu.home") to review(ReviewState.TO_REVIEW, "Accueil"),
                ReviewKey("translation", "menu.about") to review(ReviewState.APPROVED, "À propos"),
                ReviewKey("common", "cart.title") to review(ReviewState.APPROVED, "Chariot"),
            ),
            "de" to mapOf(
                ReviewKey("translation", "menu.home") to review(ReviewState.APPROVED, "Start", source = "Home"),
                ReviewKey("translation", "menu.about") to review(ReviewState.APPROVED, "Über", source = "About"),
            ),
        ),
        "translation",
        referenceLocale = "en",
    )

    @Test
    fun `reviewCounts counts per locale the keys to review, approved then changed, and stale`() {
        val total = reviewCounts(report, reviews).getValue(null)

        assertEquals(ReviewCount(toReview = 2, sourceChanged = 0), total["fr"], "menu.home to review, cart.title changed since approval")
        assertEquals(ReviewCount(toReview = 1, sourceChanged = 1), total["de"], "menu.home decided on an older source")
        assertEquals(ReviewCount(toReview = 0, sourceChanged = 0), total["en"], "nothing reviewed in the reference")
    }

    @Test
    fun `reviewCounts counts each namespace row on its own keys`() {
        val counts = reviewCounts(report, reviews)

        assertEquals(setOf(null, NamespaceFilter.Default, NamespaceFilter.Named("common")), counts.keys)
        assertEquals(ReviewCount(1, 0), counts.getValue(NamespaceFilter.Default)["fr"])
        assertEquals(ReviewCount(1, 0), counts.getValue(NamespaceFilter.Named("common"))["fr"])
        assertEquals(ReviewCount(1, 1), counts.getValue(NamespaceFilter.Default)["de"])
        assertEquals(ReviewCount(0, 0), counts.getValue(NamespaceFilter.Named("common"))["de"])
    }

    @Test
    fun `reviewCounts agrees with the table's To review and Source changed filters`() {
        val viewModel = TableViewModel()
        val rows = report.translations.map { (key, values) -> TranslationRow(key, values) }
        val total = reviewCounts(report, reviews).getValue(null)

        report.locales.forEach { locale ->
            assertEquals(viewModel.filterByStatus(StatusFilter.TO_REVIEW, rows, listOf(locale), reviews).size, total.getValue(locale).toReview, locale)
            assertEquals(viewModel.filterByStatus(StatusFilter.SOURCE_CHANGED, rows, listOf(locale), reviews).size, total.getValue(locale).sourceChanged, locale)
        }
    }

    @Test
    fun `reviewCounts is empty without reviews`() {
        assertTrue(reviewCounts(report, TableReviews.OFF).isEmpty())
    }
}
