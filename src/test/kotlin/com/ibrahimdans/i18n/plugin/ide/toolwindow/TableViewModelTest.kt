package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.references.translation.ReferencesAccumulator
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
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

    // ---- key shape ----

    @Test
    fun `namespaceOf reads the part before the colon, or nothing`() {
        assertEquals("common", viewModel.namespaceOf("common:menu.home"))
        assertNull(viewModel.namespaceOf("menu.home"))
        assertNull(viewModel.namespaceOf(":menu.home"), "a leading colon names no namespace")
    }

    @Test
    fun `keySegments drops the namespace and splits on dots`() {
        assertEquals(listOf("menu", "home"), viewModel.keySegments("common:menu.home"))
        assertEquals(listOf("menu", "home"), viewModel.keySegments("menu.home"))
        assertEquals(listOf("home"), viewModel.keySegments("home"))
    }

    // ---- usage search ----

    @Test
    fun `a namespaced key is searched under both its forms`() {
        val query = viewModel.usageQuery("navigation:menu.profile", "-")

        assertEquals("menu.profile", query.bareKey)
        assertEquals(listOf("navigation:menu.profile", "menu.profile"), query.words)
    }

    @Test
    fun `a key without a namespace is searched once`() {
        val query = viewModel.usageQuery("menu.profile", "-")

        assertEquals("menu.profile", query.bareKey)
        assertEquals(listOf("menu.profile"), query.words)
    }

    /**
     * The bug: `t('account:…addTrustee.description', { count })` is what the sources hold, while
     * the translation file holds `description_one` / `description_other`. Searching the stored
     * form found nothing, so every pluralized key was reported as an orphan — and offered for
     * deletion by *Cleanup unused keys*.
     */
    @Test
    fun `a plural form is searched under the key the sources actually write`() {
        val query = viewModel.usageQuery("account:trustees.modal.description_other", "-")

        assertEquals("trustees.modal.description", query.bareKey)
        assertEquals(
            listOf("account:trustees.modal.description", "trustees.modal.description"),
            query.words
        )
    }

    @Test
    fun `a legacy numeric plural form is searched the same way`() {
        val query = viewModel.usageQuery("cart.item-5", "-")

        assertEquals("cart.item", query.bareKey)
        assertEquals(listOf("cart.item"), query.words)
    }

    // ---- cell states ----

    /**
     * Three states the renderer used to separate by background tint alone. Naming them is what
     * lets a cell say "Missing" instead of merely being pink — and lets the distinction be
     * checked without a Swing component.
     */
    @Test
    fun `valueStatus separates no entry from an entry holding nothing`() {
        assertEquals(ValueStatus.MISSING, viewModel.valueStatus(""))
        assertEquals(ValueStatus.BLANK, viewModel.valueStatus("   "))
        assertEquals(ValueStatus.BLANK, viewModel.valueStatus("\n\t"))
        assertEquals(ValueStatus.TRANSLATED, viewModel.valueStatus("Home"))
        assertEquals(ValueStatus.TRANSLATED, viewModel.valueStatus(" Home "))
    }

    @Test
    fun `usageStatus separates never scanned from unused`() {
        // The distinction the "—" placeholder was carrying on its own: a key nobody has
        // looked for is not a key nobody uses, and deleting on that confusion loses data.
        assertEquals(UsageStatus.NOT_SCANNED, viewModel.usageStatus(-1))
        assertEquals(UsageStatus.ORPHAN, viewModel.usageStatus(0))
        assertEquals(UsageStatus.USED, viewModel.usageStatus(1))
        assertEquals(UsageStatus.USED, viewModel.usageStatus(42))
    }

    @Test
    fun `usageStatus separates a dynamically reached key from an unused one`() {
        // Its own sentinel rather than a count: nothing names the key, yet deleting it breaks
        // a call site. The cleanup already knew; the column used to say "Unused" anyway.
        assertEquals(UsageStatus.DYNAMIC, viewModel.usageStatus(TableViewModel.DYNAMIC_USAGE))
        assertEquals(UsageStatus.NOT_SCANNED, viewModel.usageStatus(-3), "an unknown negative is not dynamic")
    }

    // ---- columns ----

    @Test
    fun `visibleLocales removes the hidden ones and keeps the order`() {
        val locales = listOf("en", "fr", "de", "es")

        assertEquals(locales, viewModel.visibleLocales(locales, emptySet()))
        assertEquals(listOf("en", "de"), viewModel.visibleLocales(locales, setOf("fr", "es")))
    }

    @Test
    fun `toggleLocale hides and shows a locale again`() {
        val locales = listOf("en", "fr")

        val hidden = viewModel.toggleLocale(locales, emptySet(), "fr")
        assertEquals(setOf("fr"), hidden)
        assertEquals(emptySet<String>(), viewModel.toggleLocale(locales, hidden, "fr"))
    }

    @Test
    fun `toggleLocale refuses to hide the last visible locale`() {
        // A table left with its Key and Usage columns shows no translation at all, and
        // nothing in the interface would tell the user why.
        val locales = listOf("en", "fr")
        val hidden = setOf("fr")

        assertEquals(hidden, viewModel.toggleLocale(locales, hidden, "en"))
    }

    @Test
    fun `toggleLocale ignores a locale the module does not have`() {
        assertEquals(emptySet<String>(), viewModel.toggleLocale(listOf("en", "fr"), emptySet(), "de"))
    }

    @Test
    fun `columnWidths gives the key column more room than any other`() {
        // AUTO_RESIZE_ALL_COLUMNS used to split the viewport in equal shares: the key, the
        // longest text of the table, got exactly as much room as "Usage".
        val widths = viewModel.columnWidths(3)

        assertEquals(5, widths.size, "Key + three locales + Usage")
        assertTrue(widths.first() > widths[1], "the key column starts widest")
        assertTrue(widths.first() > widths.last(), "the usage count needs the least room")
        assertEquals(widths[1], widths[3], "every locale column starts on the same width")
    }

    @Test
    fun `columnWidths puts a narrower namespace column in front of the key when asked`() {
        val widths = viewModel.columnWidths(2, withNamespace = true)

        assertEquals(5, widths.size, "Namespace + Key + two locales + Usage")
        assertTrue(widths[0] < widths[1], "the namespace is a short word, the key is not")
        assertEquals(viewModel.columnWidths(2), widths.drop(1), "the other columns keep their width")
    }

    @Test
    fun `columnNames lays out Key, the locales, then Usage, in the order of columnWidths`() {
        val names = viewModel.columnNames(listOf("en", "fr"))

        assertEquals(viewModel.columnWidths(2).size, names.size)
        assertEquals(listOf("en", "fr"), names.subList(1, 3), "locales keep their order")
        assertEquals(PluginBundle.message("toolwindow.table.column.key"), names.first())
        assertEquals(PluginBundle.message("toolwindow.table.column.usage"), names.last())
    }

    @Test
    fun `columnNames puts the Namespace column in front of the key when asked`() {
        val names = viewModel.columnNames(listOf("en"), withNamespace = true)

        assertEquals(viewModel.columnWidths(1, withNamespace = true).size, names.size)
        assertEquals(PluginBundle.message("toolwindow.table.column.namespace"), names[0])
        assertEquals(viewModel.columnNames(listOf("en")), names.drop(1), "the other columns do not move")
    }

    @Test
    fun `rowCells holds the full key, one value per locale and the raw usage count`() {
        val row = TranslationRow("common:menu.home", mapOf("en" to "Home"), usageCount = 0)

        assertEquals(listOf("common:menu.home", "Home", "", 0), viewModel.rowCells(row, listOf("en", "fr")))
    }

    @Test
    fun `rowCells leads with the namespace label and still keeps the full key when asked`() {
        val row = TranslationRow("common:menu.home", mapOf("en" to "Home"))

        assertEquals(
            listOf("common", "common:menu.home", "Home", -1),
            viewModel.rowCells(row, listOf("en"), withNamespace = true),
        )
    }

    // ---- namespace column ----

    @Test
    fun `showsNamespaceColumn only under All when the rows span several groups`() {
        val several = listOf(NamespaceFilter.All, NamespaceFilter.Named("auth"), NamespaceFilter.Named("common"))
        val withDefault = listOf(NamespaceFilter.All, NamespaceFilter.Default, NamespaceFilter.Named("common"))
        val single = listOf(NamespaceFilter.All, NamespaceFilter.Named("common"))
        val none = listOf(NamespaceFilter.All, NamespaceFilter.Default)

        assertTrue(viewModel.showsNamespaceColumn(NamespaceFilter.All, several))
        assertTrue(viewModel.showsNamespaceColumn(NamespaceFilter.All, withDefault))
        assertFalse(viewModel.showsNamespaceColumn(NamespaceFilter.All, single), "one namespace would repeat on every row")
        assertFalse(viewModel.showsNamespaceColumn(NamespaceFilter.All, none), "a project without namespaces has nothing to show")
        assertFalse(viewModel.showsNamespaceColumn(NamespaceFilter.Named("auth"), several), "filtered to one namespace already")
        assertFalse(viewModel.showsNamespaceColumn(NamespaceFilter.Default, withDefault))
    }

    @Test
    fun `namespaceLabel and keyLabel split a key the way the two columns show it`() {
        assertEquals("common", viewModel.namespaceLabel("common:menu.home"))
        assertEquals("menu.home", viewModel.keyLabel("common:menu.home"))

        assertEquals(NamespaceFilter.Default.label(Config()), viewModel.namespaceLabel("menu.home"))
        assertEquals("menu.home", viewModel.keyLabel("menu.home"))
    }

    @Test
    fun `the default group names its namespace when the configuration has exactly one`() {
        val one = Config(defaultNs = "common")
        val several = Config(defaultNs = "common, shared")

        assertEquals("common (default)", NamespaceFilter.Default.label(one))
        assertEquals(NamespaceFilter.Default.label, NamespaceFilter.Default.label(several), "no single name to show")
        assertEquals("common (default)", viewModel.namespaceLabel("menu.home", one))
        assertEquals("auth", viewModel.namespaceLabel("auth:menu.home", one), "a named group never changes")
        assertEquals(NamespaceFilter.All.label, NamespaceFilter.All.label(one))
    }

    @Test
    fun `the namespace column leads with the default group, then names alphabetically`() {
        val order = viewModel.namespaceOrder(Config(defaultNs = "common"))

        assertEquals(
            listOf("common (default)", "auth", "dashboard", "navigation"),
            listOf("navigation", "common (default)", "dashboard", "auth").sortedWith(order),
        )
    }

    // ---- source routing ----

    private fun source(displayPath: String, name: String, parent: String) =
        LocalizationSource(tree = null, name = name, parent = parent, displayPath = displayPath, localization = mockk())

    @Test
    fun `findSourceFor picks the file of the asked locale`() {
        val en = source("src/locales/en.json", "en.json", "en")
        val fr = source("src/locales/fr.json", "fr.json", "fr")
        every { TranslationDataLoader.findSources(project, null) } returns listOf(en, fr)
        every { TranslationDataLoader.extractLocale(en) } returns "en"
        every { TranslationDataLoader.extractLocale(fr) } returns "fr"

        assertEquals(fr, viewModel.findSourceFor(project, "menu.home", "fr"))
    }

    @Test
    fun `findSourceFor also matches the namespace the key carries`() {
        // Same locale, two namespaces: routing on the locale alone wrote "common:menu.home"
        // into whichever file came first.
        val common = source("src/locales/en/common.json", "common.json", "en")
        val auth = source("src/locales/en/auth.json", "auth.json", "en")
        every { TranslationDataLoader.findSources(project, null) } returns listOf(common, auth)
        every { TranslationDataLoader.extractLocale(common) } returns "en"
        every { TranslationDataLoader.extractLocale(auth) } returns "en"
        every { TranslationDataLoader.extractNamespace(common, any()) } returns "common"
        every { TranslationDataLoader.extractNamespace(auth, any()) } returns "auth"

        assertEquals(auth, viewModel.findSourceFor(project, "auth:login.title", "en"))
    }

    @Test
    fun `findSourceFor returns nothing when the locale has no file`() {
        val en = source("src/locales/en.json", "en.json", "en")
        every { TranslationDataLoader.findSources(project, null) } returns listOf(en)
        every { TranslationDataLoader.extractLocale(en) } returns "en"

        assertNull(viewModel.findSourceFor(project, "menu.home", "de"))
    }

    // ── mergeUsages ───────────────────────────────────────────────────────────

    @Test
    fun `mergeUsages keeps the count already known for a key still present`() {
        // A reload follows every translation file change, the table's own edits included:
        // without the merge one corrected value threw away a whole project scan.
        val fresh = listOf(TranslationRow("menu.home", mapOf("en" to "Home")))
        val previous = listOf(TranslationRow("menu.home", mapOf("en" to "Home"), usageCount = 3))

        assertEquals(3, viewModel.mergeUsages(fresh, previous).single().usageCount)
    }

    @Test
    fun `mergeUsages keeps orphan and dynamic verdicts too`() {
        val fresh = listOf(TranslationRow("a", emptyMap()), TranslationRow("b", emptyMap()))
        val previous = listOf(
            TranslationRow("a", emptyMap(), usageCount = 0),
            TranslationRow("b", emptyMap(), usageCount = TableViewModel.DYNAMIC_USAGE),
        )

        assertEquals(listOf(0, TableViewModel.DYNAMIC_USAGE), viewModel.mergeUsages(fresh, previous).map { it.usageCount })
    }

    @Test
    fun `mergeUsages leaves a new key not scanned`() {
        val fresh = listOf(TranslationRow("menu.home", emptyMap()), TranslationRow("menu.new", emptyMap()))
        val previous = listOf(TranslationRow("menu.home", emptyMap(), usageCount = 2))

        val merged = viewModel.mergeUsages(fresh, previous)

        assertEquals(listOf(2, -1), merged.map { it.usageCount })
    }

    @Test
    fun `mergeUsages drops a deleted key`() {
        val fresh = listOf(TranslationRow("menu.home", emptyMap()))
        val previous = listOf(
            TranslationRow("menu.home", emptyMap(), usageCount = 2),
            TranslationRow("menu.gone", emptyMap(), usageCount = 0),
        )

        assertEquals(listOf("menu.home"), viewModel.mergeUsages(fresh, previous).map { it.key })
    }

    @Test
    fun `mergeUsages keeps the count of a key whose value changed and takes the new value`() {
        // The count comes from the source code, not from the value: editing a translation
        // does not change how often the key is called.
        val fresh = listOf(TranslationRow("menu.home", mapOf("en" to "Start")))
        val previous = listOf(TranslationRow("menu.home", mapOf("en" to "Home"), usageCount = 4))

        val merged = viewModel.mergeUsages(fresh, previous).single()

        assertEquals(4, merged.usageCount)
        assertEquals(mapOf("en" to "Start"), merged.values)
    }

    @Test
    fun `mergeUsages keeps the order of the fresh rows`() {
        val fresh = listOf(TranslationRow("b", emptyMap()), TranslationRow("a", emptyMap()))
        val previous = listOf(TranslationRow("a", emptyMap(), usageCount = 1), TranslationRow("b", emptyMap(), usageCount = 2))

        assertEquals(listOf("b" to 2, "a" to 1), viewModel.mergeUsages(fresh, previous).map { it.key to it.usageCount })
    }

    // --- status filter tests ---

    private val statusRows = listOf(
        TranslationRow("common:done", mapOf("en" to "Done", "fr" to "Fini", "de" to "Fertig"), usageCount = 3),
        TranslationRow("common:noFr", mapOf("en" to "Home", "de" to "Start"), usageCount = 0),
        TranslationRow("common:blankFr", mapOf("en" to "Save", "fr" to "  ", "de" to "Speichern")),
        TranslationRow("auth:noDe", mapOf("en" to "Login", "fr" to "Connexion"), usageCount = 0),
        TranslationRow("auth:noFr", mapOf("en" to "Logout", "de" to "Abmelden"), usageCount = 1),
    )
    private val allLocales = listOf("en", "fr", "de")

    @Test
    fun `filterByStatus ALL keeps every row`() {
        assertEquals(statusRows, viewModel.filterByStatus(StatusFilter.ALL, statusRows, allLocales))
    }

    @Test
    fun `filterByStatus MISSING keeps rows lacking an entry in a shown locale`() {
        val kept = viewModel.filterByStatus(StatusFilter.MISSING, statusRows, allLocales).map { it.key }

        assertEquals(listOf("common:noFr", "auth:noDe", "auth:noFr"), kept)
    }

    @Test
    fun `filterByStatus BLANK keeps rows whose entry is blank, not the missing ones`() {
        val kept = viewModel.filterByStatus(StatusFilter.BLANK, statusRows, allLocales).map { it.key }

        assertEquals(listOf("common:blankFr"), kept)
    }

    @Test
    fun `a hidden locale does not hold a row back`() {
        // `de` hidden: auth:noDe has nothing missing in the locales on screen.
        val shown = viewModel.visibleLocales(allLocales, setOf("de"))

        val kept = viewModel.filterByStatus(StatusFilter.MISSING, statusRows, shown).map { it.key }

        assertEquals(listOf("common:noFr", "auth:noFr"), kept)
    }

    @Test
    fun `a hidden locale holding the only blank value leaves BLANK empty`() {
        val shown = viewModel.visibleLocales(allLocales, setOf("fr"))

        assertTrue(viewModel.filterByStatus(StatusFilter.BLANK, statusRows, shown).isEmpty())
    }

    @Test
    fun `filterByStatus ORPHAN keeps the rows the scan found unused only`() {
        val kept = viewModel.filterByStatus(StatusFilter.ORPHAN, statusRows, allLocales).map { it.key }

        // common:blankFr is not scanned (-1): not known to be unused.
        assertEquals(listOf("common:noFr", "auth:noDe"), kept)
    }

    @Test
    fun `filterByStatus ORPHAN does not count a dynamically reached key`() {
        val rows = listOf(TranslationRow("status.ok", emptyMap(), usageCount = TableViewModel.DYNAMIC_USAGE))

        assertTrue(viewModel.filterByStatus(StatusFilter.ORPHAN, rows, allLocales).isEmpty())
    }

    @Test
    fun `ORPHAN is unavailable until some key was scanned`() {
        val unscanned = listOf(TranslationRow("a", emptyMap()), TranslationRow("b", emptyMap()))

        assertFalse(viewModel.isStatusFilterAvailable(StatusFilter.ORPHAN, unscanned))
        assertTrue(viewModel.isStatusFilterAvailable(StatusFilter.ORPHAN, unscanned + TranslationRow("c", emptyMap(), 2)))
        for (filter in listOf(StatusFilter.ALL, StatusFilter.MISSING, StatusFilter.BLANK)) {
            assertTrue(viewModel.isStatusFilterAvailable(filter, unscanned), "$filter needs no scan")
        }
    }

    @Test
    fun `visibleRows combines status with namespace and text`() {
        val missingInAuth = viewModel.visibleRows(
            statusRows, "", NamespaceFilter.Named("auth"), StatusFilter.MISSING, allLocales,
        )
        assertEquals(listOf("auth:noDe", "auth:noFr"), missingInAuth.map { it.key })

        // The text filter narrows further, on values as well as keys.
        val loginMissing = viewModel.visibleRows(
            statusRows, "connexion", NamespaceFilter.Named("auth"), StatusFilter.MISSING, allLocales,
        )
        assertEquals(listOf("auth:noDe"), loginMissing.map { it.key })

        val unusedInCommon = viewModel.visibleRows(
            statusRows, "", NamespaceFilter.Named("common"), StatusFilter.ORPHAN, allLocales,
        )
        assertEquals(listOf("common:noFr"), unusedInCommon.map { it.key })
    }

    @Test
    fun `visibleRows with every filter neutral returns the rows unchanged`() {
        assertEquals(statusRows, viewModel.visibleRows(statusRows, "", NamespaceFilter.All, StatusFilter.ALL, allLocales))
    }

    @Test
    fun `a status filter shows its translated label, not its name`() {
        for (filter in StatusFilter.entries) {
            assertEquals(PluginBundle.message("toolwindow.table.status.${filter.name.lowercase()}"), filter.label)
        }
    }

    // --- hiddenAmong tests ---

    @Test
    fun `hiddenAmong drops a saved locale the project no longer has`() {
        assertEquals(setOf("de"), viewModel.hiddenAmong(listOf("en", "de"), setOf("de", "it")))
    }

    @Test
    fun `hiddenAmong ignores a saved set that would hide every loaded locale`() {
        assertEquals(emptySet<String>(), viewModel.hiddenAmong(listOf("de"), setOf("de", "fr")))
    }

    @Test
    fun `hiddenAmong with nothing loaded yet hides nothing`() {
        assertEquals(emptySet<String>(), viewModel.hiddenAmong(emptyList(), setOf("de")))
    }
}
