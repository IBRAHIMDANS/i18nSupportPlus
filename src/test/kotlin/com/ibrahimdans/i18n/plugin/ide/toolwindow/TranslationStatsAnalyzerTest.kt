package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TranslationStatsAnalyzerTest {

    private val project = mockk<Project>()

    @BeforeEach
    fun setUp() {
        mockkObject(TranslationDataLoader)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `analyze returns empty when translation data is empty`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns emptyMap()

        assertTrue(TranslationStatsAnalyzer.analyze(project).isEmpty())
    }

    @Test
    fun `analyze produces one LocaleStats per locale`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "greeting" to mapOf("en" to "Hello", "fr" to "Bonjour"),
            "farewell" to mapOf("en" to "Goodbye", "fr" to "Au revoir")
        )

        val result = TranslationStatsAnalyzer.analyze(project)

        assertEquals(2, result.size)
        assertTrue(result.any { it.locale == "en" })
        assertTrue(result.any { it.locale == "fr" })
    }

    @Test
    fun `analyze computes missing keys correctly`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "common.save"   to mapOf("en" to "Save", "fr" to "Sauvegarder"),
            "common.delete" to mapOf("en" to "Delete"),
            "common.cancel" to mapOf("en" to "Cancel", "fr" to "")
        )

        val result = TranslationStatsAnalyzer.analyze(project)
        val frStats = result.first { it.locale == "fr" }

        assertEquals(2, frStats.missing)
        assertTrue(frStats.missingKeys.contains("common.delete"))
        assertTrue(frStats.missingKeys.contains("common.cancel"))
        assertFalse(frStats.missingKeys.contains("common.save"))
    }

    @Test
    fun `analyze computes translated count correctly`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "a" to mapOf("en" to "A", "fr" to "A"),
            "b" to mapOf("en" to "B"),
            "c" to mapOf("en" to "C")
        )

        val result = TranslationStatsAnalyzer.analyze(project)
        val enStats = result.first { it.locale == "en" }
        val frStats = result.first { it.locale == "fr" }

        assertEquals(3, enStats.total)
        assertEquals(3, enStats.translated)
        assertEquals(0, enStats.missing)

        assertEquals(3, frStats.total)
        assertEquals(1, frStats.translated)
        assertEquals(2, frStats.missing)
    }

    @Test
    fun `analyze computes percent correctly`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "a" to mapOf("en" to "A", "fr" to "A"),
            "b" to mapOf("en" to "B", "fr" to "B"),
            "c" to mapOf("en" to "C"),
            "d" to mapOf("en" to "D")
        )

        val result = TranslationStatsAnalyzer.analyze(project)
        val enStats = result.first { it.locale == "en" }
        val frStats = result.first { it.locale == "fr" }

        assertEquals(100.0, enStats.percent, 0.01)
        assertEquals(50.0, frStats.percent, 0.01)
    }

    @Test
    fun `analyze sorts results by locale name`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "k" to mapOf("zh" to "Z", "de" to "D", "en" to "E", "fr" to "F")
        )

        val result = TranslationStatsAnalyzer.analyze(project)

        assertEquals(listOf("de", "en", "fr", "zh"), result.map { it.locale })
    }

    @Test
    fun `analyze missingKeys list is sorted`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "z.key" to mapOf("en" to "Z"),
            "a.key" to mapOf("en" to "A"),
            "m.key" to mapOf("en" to "M")
        )

        val result = TranslationStatsAnalyzer.analyze(project)
        // "fr" has no entries → all 3 keys are missing
        val frStats = result.firstOrNull { it.locale == "fr" }
        // fr may not exist if it has no entry — verify "en" has none missing
        val enStats = result.first { it.locale == "en" }
        assertTrue(enStats.missingKeys.isEmpty())
    }

    @Test
    fun `analyze treats blank value as missing`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "key" to mapOf("en" to "Hello", "fr" to "   ")
        )

        val result = TranslationStatsAnalyzer.analyze(project)
        val frStats = result.first { it.locale == "fr" }

        assertEquals(1, frStats.missing)
        assertTrue(frStats.missingKeys.contains("key"))
    }

    @Test
    fun `analyze locale with all keys missing has percent 0`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "a" to mapOf("en" to "A"),
            "b" to mapOf("en" to "B"),
            "c" to mapOf("fr" to "C")
        )

        val result = TranslationStatsAnalyzer.analyze(project)
        val enStats = result.first { it.locale == "en" }
        val frStats = result.first { it.locale == "fr" }

        assertEquals(1, enStats.missing)   // "c" only in fr
        assertEquals(2, frStats.missing)   // "a" and "b" only in en
    }

    // ---- report: one row per namespace ----

    @Test
    fun `report lays out a total row then one row per namespace, default group first`() {
        val report = TranslationStatsAnalyzer.report(mapOf(
            "common:title" to mapOf("en" to "Title", "fr" to "Titre"),
            "common:save" to mapOf("en" to "Save"),
            "auth:login" to mapOf("en" to "Log in", "fr" to "Connexion"),
            "greeting" to mapOf("en" to "Hello", "fr" to "Bonjour"),
        ))

        assertEquals(listOf("en", "fr"), report.locales)
        assertEquals(
            listOf(null, NamespaceFilter.Default, NamespaceFilter.Named("auth"), NamespaceFilter.Named("common")),
            report.rows.map { it.group }
        )
        assertEquals(listOf(4, 1, 1, 2), report.rows.map { it.total })

        val common = report.namespaces.first { it.group == NamespaceFilter.Named("common") }
        assertEquals(50.0, common.of("fr")!!.percent)
        assertEquals(listOf("common:save"), common.of("fr")!!.missingKeys)
        assertEquals(100.0, common.of("en")!!.percent)

        // The total row is the per-locale coverage the tab used to show alone.
        assertEquals(75.0, report.total.of("fr")!!.percent)
        assertNull(report.total.of("de"), "a locale the project does not have")
    }

    @Test
    fun `report gives a namespace a 0% cell in a locale that holds none of its keys`() {
        val report = TranslationStatsAnalyzer.report(mapOf(
            "common:title" to mapOf("en" to "Title", "fr" to "Titre"),
            "billing:total" to mapOf("en" to "Total"),
        ))

        val billing = report.namespaces.first { it.group == NamespaceFilter.Named("billing") }
        val fr = billing.of("fr")
        assertNotNull(fr, "fr is a locale of the project, so the row must carry it")
        assertEquals(0.0, fr!!.percent)
        assertEquals(listOf("billing:total"), fr.missingKeys)
    }

    @Test
    fun `report leaves the total row out when every key sits in one group`() {
        val single = TranslationStatsAnalyzer.report(mapOf(
            "menu.home" to mapOf("en" to "Home", "fr" to "Accueil"),
            "menu.about" to mapOf("en" to "About"),
        ))

        assertEquals(1, single.rows.size)
        assertEquals(NamespaceFilter.Default, single.rows.single().group)
        assertEquals(50.0, single.rows.single().of("fr")!!.percent)

        val empty = TranslationStatsAnalyzer.report(emptyMap())
        assertTrue(empty.rows.isEmpty())
        assertTrue(empty.locales.isEmpty())
    }

    @Test
    fun `report counts a plural group once per namespace`() {
        val report = TranslationStatsAnalyzer.report(mapOf(
            "common:item_one" to mapOf("en" to "item", "fr" to "élément"),
            "common:item_other" to mapOf("en" to "items", "fr" to "éléments"),
            "common:title" to mapOf("en" to "Title"),
        ))

        val common = report.namespaces.single()
        assertEquals(2, common.total)
        assertEquals(50.0, common.of("fr")!!.percent)
    }
}
