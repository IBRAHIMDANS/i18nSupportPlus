package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.testFramework.PlatformTestUtil
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.Container
import javax.swing.JTable

/**
 * What the statistics tab lays out, through its public component tree only — the
 * analyzer's arithmetic is `TranslationStatsAnalyzerTest`'s.
 */
class TranslationStatsPanelLayoutTest : PlatformBaseTest() {

    @AfterEach
    fun tearDownMocks() = unmockkAll()

    @Test
    fun `several namespaces lay out a total row then one row per namespace, a column per locale`() {
        stub(
            "common:title" to mapOf("en" to "Title", "fr" to "Titre"),
            "common:save" to mapOf("en" to "Save"),
            "auth:login" to mapOf("en" to "Log in", "fr" to "Connexion"),
        )
        val table = loadedTable(TranslationStatsPanel(project))

        assertEquals(4, table.columnCount, "Namespace + Keys + en + fr")
        assertEquals(PluginBundle.message("toolwindow.stats.column.namespace"), table.getColumnName(0))
        assertEquals(PluginBundle.message("toolwindow.stats.column.keys"), table.getColumnName(1))
        assertEquals("en", table.getColumnName(2))
        assertEquals("fr", table.getColumnName(3))

        assertEquals(3, table.rowCount)
        assertEquals(PluginBundle.message("toolwindow.stats.row.total"), table.getValueAt(0, 0))
        assertEquals(3, table.getValueAt(0, 1))
        assertEquals("auth", table.getValueAt(1, 0))
        assertEquals("common", table.getValueAt(2, 0))
        assertEquals(2, table.getValueAt(2, 1))
        assertEquals("100.0%", table.getValueAt(2, 2))
        assertEquals("50.0%", table.getValueAt(2, 3))
        assertEquals("66.7%", table.getValueAt(0, 3))
    }

    @Test
    fun `a single namespace is one row, without a total repeating it`() {
        stub("menu.home" to mapOf("en" to "Home", "fr" to "Accueil"))
        val table = loadedTable(TranslationStatsPanel(project))

        assertEquals(1, table.rowCount)
        assertEquals(NamespaceFilter.Default.label, table.getValueAt(0, 0))
        assertNull(table.rowSorter, "rows keep the report's order")
    }

    private fun stub(vararg translations: Pair<String, Map<String, String>>) {
        mockkObject(TranslationDataLoader)
        every { TranslationDataLoader.loadAllTranslations(project, null) } returns mapOf(*translations)
    }

    private fun loadedTable(panel: TranslationStatsPanel): JTable {
        val table = find(panel, JTable::class.java)!!
        panel.refresh()
        val deadline = System.currentTimeMillis() + 15_000
        while (table.rowCount == 0 && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        return table
    }

    private fun <T> find(root: Container, type: Class<T>): T? {
        for (child in root.components) {
            if (type.isInstance(child)) return type.cast(child)
            if (child is Container) find(child, type)?.let { return it }
        }
        return null
    }
}
