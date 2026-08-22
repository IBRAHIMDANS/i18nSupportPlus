package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.references.translation.ReferencesAccumulator
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TableViewModelTest {

    private val project = mockk<Project>()
    private val viewModel = TableViewModel()

    @BeforeEach
    fun setUp() {
        mockkObject(TranslationDataLoader)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // --- loadRows tests ---

    @Test
    fun `loadRows returns empty list when no translations exist`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns emptyMap()

        val rows = viewModel.loadRows(project)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `loadRows maps each key to a TranslationRow`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "greeting" to mapOf("en" to "Hello", "fr" to "Bonjour"),
            "farewell" to mapOf("en" to "Goodbye")
        )

        val rows = viewModel.loadRows(project)

        assertEquals(2, rows.size)
        val greetingRow = rows.find { it.key == "greeting" }!!
        assertEquals("Hello", greetingRow.values["en"])
        assertEquals("Bonjour", greetingRow.values["fr"])

        val farewellRow = rows.find { it.key == "farewell" }!!
        assertEquals("Goodbye", farewellRow.values["en"])
        assertNull(farewellRow.values["fr"])
    }

    @Test
    fun `loadRows returns rows sorted alphabetically by key`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "z.last" to mapOf("en" to "Last"),
            "a.first" to mapOf("en" to "First"),
            "m.middle" to mapOf("en" to "Middle")
        )

        val rows = viewModel.loadRows(project)

        assertEquals(3, rows.size)
        assertEquals("a.first", rows[0].key)
        assertEquals("m.middle", rows[1].key)
        assertEquals("z.last", rows[2].key)
    }

    @Test
    fun `loadRows handles keys with empty locale values`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "empty" to emptyMap()
        )

        val rows = viewModel.loadRows(project)

        assertEquals(1, rows.size)
        assertEquals("empty", rows[0].key)
        assertTrue(rows[0].values.isEmpty())
    }

    @Test
    fun `loadRows preserves all locale values per row`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "nav.home" to mapOf("en" to "Home", "fr" to "Accueil", "de" to "Startseite", "es" to "Inicio")
        )

        val rows = viewModel.loadRows(project)

        assertEquals(1, rows.size)
        assertEquals(4, rows[0].values.size)
        assertEquals("Home", rows[0].values["en"])
        assertEquals("Accueil", rows[0].values["fr"])
        assertEquals("Startseite", rows[0].values["de"])
        assertEquals("Inicio", rows[0].values["es"])
    }

    @Test
    fun `loadRows produces rows with default usageCount of -1`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "menu.home" to mapOf("en" to "Home")
        )

        val rows = viewModel.loadRows(project)

        assertEquals(1, rows.size)
        assertEquals(-1, rows[0].usageCount)
    }

    // --- getLocales tests ---

    @Test
    fun `getLocales returns empty list when no sources exist`() {
        every { TranslationDataLoader.discoverLocales(project) } returns emptyList()

        val locales = viewModel.getLocales(project)
        assertTrue(locales.isEmpty())
    }

    @Test
    fun `getLocales returns discovered locales`() {
        every { TranslationDataLoader.discoverLocales(project) } returns listOf("de", "en", "fr")

        val locales = viewModel.getLocales(project)

        assertEquals(3, locales.size)
        assertEquals(listOf("de", "en", "fr"), locales)
    }

    @Test
    fun `getLocales delegates to TranslationDataLoader`() {
        every { TranslationDataLoader.discoverLocales(project) } returns listOf("ja")

        val locales = viewModel.getLocales(project)
        assertEquals(listOf("ja"), locales)
    }

    // --- filter tests ---

    @Test
    fun `filter returns all rows when query is blank`() {
        val rows = listOf(
            TranslationRow("menu.home", mapOf("en" to "Home")),
            TranslationRow("menu.back", mapOf("en" to "Back"))
        )
        assertEquals(rows, viewModel.filter("", rows))
    }

    @Test
    fun `filter matches on key substring`() {
        val rows = listOf(
            TranslationRow("menu.home", mapOf("en" to "Home")),
            TranslationRow("footer.link", mapOf("en" to "Link"))
        )
        val result = viewModel.filter("menu", rows)
        assertEquals(1, result.size)
        assertEquals("menu.home", result[0].key)
    }

    @Test
    fun `filter matches on translation value`() {
        val rows = listOf(
            TranslationRow("a", mapOf("en" to "Hello World")),
            TranslationRow("b", mapOf("en" to "Goodbye"))
        )
        val result = viewModel.filter("hello", rows)
        assertEquals(1, result.size)
        assertEquals("a", result[0].key)
    }

    // --- TranslationRow usageCount tests ---

    @Test
    fun `TranslationRow usageCount defaults to -1`() {
        val row = TranslationRow("key", emptyMap())
        assertEquals(-1, row.usageCount)
    }

    @Test
    fun `TranslationRow copy preserves usageCount`() {
        val row = TranslationRow("key", emptyMap(), usageCount = 3)
        val copied = row.copy(key = "other")
        assertEquals(3, copied.usageCount)
    }

    @Test
    fun `TranslationRow with usageCount 0 represents orphan`() {
        val row = TranslationRow("orphan.key", mapOf("en" to "value"), usageCount = 0)
        assertEquals(0, row.usageCount)
    }

    // --- Integration-like scenarios ---

    @Test
    fun `rows correctly reflect missing locale for some keys`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "common.save" to mapOf("en" to "Save", "fr" to "Sauvegarder"),
            "common.delete" to mapOf("en" to "Delete"),
            "common.cancel" to mapOf("fr" to "Annuler")
        )

        val rows = viewModel.loadRows(project)

        assertEquals(3, rows.size)

        val save = rows.find { it.key == "common.save" }!!
        assertEquals(2, save.values.size)

        val delete = rows.find { it.key == "common.delete" }!!
        assertEquals(1, delete.values.size)
        assertNull(delete.values["fr"])

        val cancel = rows.find { it.key == "common.cancel" }!!
        assertEquals(1, cancel.values.size)
        assertNull(cancel.values["en"])
    }

    // ---- namespace filter ----

    private fun rows(vararg keys: String) = keys.map { TranslationRow(it, mapOf("en" to "v")) }

    @Test
    fun `namespaceFilters offers All, then Default, then each namespace sorted`() {
        val filters = viewModel.namespaceFilters(rows("zeta:a", "common:b", "bare.key", "auth:c"))

        assertEquals(
            listOf(
                NamespaceFilter.All,
                NamespaceFilter.Default,
                NamespaceFilter.Named("auth"),
                NamespaceFilter.Named("common"),
                NamespaceFilter.Named("zeta"),
            ),
            filters
        )
    }

    @Test
    fun `namespaceFilters omits Default when every key carries a namespace`() {
        val filters = viewModel.namespaceFilters(rows("common:a", "auth:b"))

        assertFalse(filters.contains(NamespaceFilter.Default), "no key is namespace-less here")
        assertEquals(NamespaceFilter.All, filters.first())
    }

    @Test
    fun `All keeps every row`() {
        val all = rows("common:a", "bare")
        assertEquals(all, viewModel.filterByNamespace(NamespaceFilter.All, all))
    }

    @Test
    fun `Default keeps only the rows whose key has no namespace`() {
        val filtered = viewModel.filterByNamespace(NamespaceFilter.Default, rows("common:a", "bare", "auth:b"))

        assertEquals(listOf("bare"), filtered.map { it.key })
    }

    @Test
    fun `Named keeps only its own namespace`() {
        val filtered = viewModel.filterByNamespace(NamespaceFilter.Named("common"), rows("common:a", "commonly:b", "auth:c"))

        assertEquals(listOf("common:a"), filtered.map { it.key }, "the prefix must match up to the colon")
    }

    /**
     * The regression the typed model exists for. `All` used to *be* the translated label
     * `toolwindow.table.namespace.all`, compared with `==`: a project owning a namespace named
     * like that label selected it and saw every row instead of that namespace's rows. `Default`
     * had the same collision on the literal `"(default)"`.
     */
    @Test
    fun `a namespace named like a label is filtered as itself, not as the label`() {
        val labelled = rows("All namespaces:a", "(default):b", "other:c")

        assertEquals(
            listOf("All namespaces:a"),
            viewModel.filterByNamespace(NamespaceFilter.Named("All namespaces"), labelled).map { it.key }
        )
        assertEquals(
            listOf("(default):b"),
            viewModel.filterByNamespace(NamespaceFilter.Named("(default)"), labelled).map { it.key }
        )
    }
}
