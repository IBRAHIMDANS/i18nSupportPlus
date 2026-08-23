package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.PlatformTestUtil
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.JTable

/**
 * Characterization tests for [TableViewPanel] — what it does today, not what it should do.
 *
 * The panel is the largest file in the repository and carries rendering, the table model,
 * in-place editing and the delete actions, with nothing covering any of it: the two tests
 * in `I18nToolWindowPanelTest` that build a panel are named `ignoredTest…` and never run,
 * because `I18nToolWindowPanel` needs an `ActionManager` the headless container does not
 * provide. `TableViewPanel` does not — it builds plain Swing — so it *can* be pinned down,
 * and this class does it through the public component tree only, touching no production code.
 *
 * The point is to make the extraction described in TASK-TABLEVIEW-SPLIT verifiable. Until
 * something covers the panel, moving code out of it is a change nobody can check.
 */
class TableViewPanelTest : PlatformBaseTest() {

    @AfterEach
    fun tearDownMocks() = unmockkAll()

    /** Breadth-first, so a component is found before the widgets nested inside its siblings. */
    private fun <T> find(root: Container, type: Class<T>): T? {
        val queue = ArrayDeque<Container>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            for (child in next.components) {
                if (type.isInstance(child)) return type.cast(child)
            }
            for (child in next.components) if (child is Container) queue.add(child)
        }
        return null
    }

    @Test
    fun `the panel builds in a headless container`() {
        // Guard, not a formality: I18nToolWindowPanel lost this the day it reached for
        // ActionManager, and its two panel tests have been skipped ever since.
        assertNotNull(TableViewPanel(project), "TableViewPanel must stay buildable without a running IDE")
    }

    @Test
    fun `the filter bar offers every namespace and the orphan scan`() {
        val panel = TableViewPanel(project)

        val label = find(panel, JLabel::class.java)
        val combo = find(panel, JComboBox::class.java)
        val button = find(panel, JButton::class.java)

        assertEquals(PluginBundle.message("toolwindow.table.namespace.label") + " ", label?.text)
        assertEquals(PluginBundle.message("toolwindow.table.scan.orphans"), button?.text)
        assertEquals(1, combo?.itemCount, "before any load the combo offers the All filter alone")
        assertEquals(NamespaceFilter.All, combo?.selectedItem)
    }

    @Test
    fun `the namespace combo shows a filter's label, not its toString`() {
        // The combo holds NamespaceFilter values and not strings since #193, which is what
        // separates the identity the filter compares from the text the user reads. The model
        // side is tested; this is the renderer that makes it visible.
        val panel = TableViewPanel(project)
        @Suppress("UNCHECKED_CAST")
        val combo = find(panel, JComboBox::class.java)!! as JComboBox<NamespaceFilter>
        val filter = NamespaceFilter.Named("common")

        val rendered = combo.renderer.getListCellRendererComponent(
            JList<NamespaceFilter>(), filter, 0, false, false
        ) as JLabel

        assertEquals(filter.label, rendered.text)
        assertFalse(rendered.text.contains("NamespaceFilter"), "a data class toString would leak here")
    }

    @Test
    fun `only the locale columns are editable`() {
        // Column 0 is the key — renaming belongs to RenameI18nKeyHandler — and the last one
        // is the computed usage count. Everything between is written straight to the files.
        val panel = TableViewPanel(project)
        val table = find(panel, JTable::class.java)!!
        val model = table.model as javax.swing.table.DefaultTableModel

        model.setDataVector(
            arrayOf(arrayOf<Any>("menu.home", "Home", "Accueil", "3")),
            arrayOf<Any>("Key", "en", "fr", "Usage")
        )

        assertFalse(model.isCellEditable(0, 0), "the key column stays read-only")
        assertTrue(model.isCellEditable(0, 1), "en is editable in place")
        assertTrue(model.isCellEditable(0, 2), "fr is editable in place")
        assertFalse(model.isCellEditable(0, 3), "the usage count is computed, not typed")
    }

    @Test
    fun `refresh lays out key, locale and usage columns and spells out the usage count`() {
        stubTranslations()
        val panel = TableViewPanel(project)
        val table = loadedTable(panel)

        assertEquals(4, table.columnCount, "Key + en + fr + Usage")
        assertEquals(PluginBundle.message("toolwindow.table.column.key"), table.getColumnName(0))
        assertEquals("en", table.getColumnName(1))
        assertEquals("fr", table.getColumnName(2))
        assertEquals(PluginBundle.message("toolwindow.table.column.usage"), table.getColumnName(3))

        assertEquals(2, table.rowCount)
        // Asserted by name, not by count: two locales and two keys are both 2, and a row count
        // alone happily passes on a table whose key column lists the locales.
        assertEquals("menu.about", table.model.getValueAt(0, 0))
        assertEquals("menu.home", table.model.getValueAt(1, 0))
        assertEquals("Accueil", table.model.getValueAt(1, 2))
        // Nothing has been scanned yet, so every row reads as not scanned rather than as zero —
        // the difference between "no usage found" and "never looked".
        for (row in 0 until table.rowCount) {
            // assertEquals(expected, actual, message) is NOT usable when the expected value is a
            // String: BasePlatformTestCase inherits junit.framework's assertEquals(message,
            // expected, actual), whose first parameter is also a String, and it wins the overload.
            assertTrue(table.getValueAt(row, 3) == "—", "an unscanned row must not read as an orphan")
        }
    }

    // ── Edition en place ──────────────────────────────────────────────────────

    /** Loads two rows through the panel's own refresh and returns its table, populated. */
    private fun loadedTable(panel: TableViewPanel): JTable {
        val table = find(panel, JTable::class.java)!!
        panel.refresh()
        val deadline = System.currentTimeMillis() + 15_000
        while (table.columnCount == 0 && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        return table
    }

    /**
     * `loadAllTranslations` is keyed by translation key, each entry holding its locales — not the
     * other way round. Getting that backwards produced a table whose key column read `en` and `fr`,
     * with two rows, which is exactly what a row count alone would have failed to catch.
     */
    private fun stubTranslations() {
        mockkObject(TranslationDataLoader)
        every { TranslationDataLoader.loadAllTranslations(project, null) } returns mapOf(
            "menu.home" to mapOf("en" to "Home", "fr" to "Accueil"),
            "menu.about" to mapOf("en" to "About", "fr" to "À propos"),
        )
        every { TranslationDataLoader.discoverLocales(project, null) } returns listOf("en", "fr")
    }

    /**
     * `saveValue` itself is covered by `TableViewModelSaveValueTest`, on real files, in six cases.
     * What belongs here is only what the panel does with the boolean it gets back — and the
     * fixture holds no translation file, so the real write refuses, which is the case to pin.
     */
    @Test
    fun `a refused write leaves the cell on its previous value and says so`() {
        stubTranslations()
        val panel = TableViewPanel(project)
        val table = loadedTable(panel)

        var dialogs = 0
        val previous = TestDialogManager.setTestDialog(TestDialog { dialogs++; 0 })
        try {
            table.model.setValueAt("Bonjour", 0, 2)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }

        assertTrue(table.model.getValueAt(0, 2) == "À propos", "the cell must keep what is actually on disk")
        assertEquals(1, dialogs, "a refused write has to be reported, not swallowed")
    }

    @Test
    fun `retyping the same value attempts no write at all`() {
        stubTranslations()
        val panel = TableViewPanel(project)
        val table = loadedTable(panel)

        var dialogs = 0
        val previous = TestDialogManager.setTestDialog(TestDialog { dialogs++; 0 })
        try {
            table.model.setValueAt("À propos", 0, 2)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }

        // Leaving a cell editor without changing anything is the common case; it must not
        // reach the files, and must not raise the failure dialog the fixture would trigger.
        assertEquals(0, dialogs, "an unchanged value is not a write")
        assertTrue(table.model.getValueAt(0, 2) == "À propos")
    }

    // ── Renderers ─────────────────────────────────────────────────────────────

    @Test
    fun `a locale cell is shown on one line with the raw value in its tooltip`() {
        stubTranslations()
        val panel = TableViewPanel(project)
        val table = loadedTable(panel)
        val renderer = table.columnModel.getColumn(1).cellRenderer

        val rendered = renderer.getTableCellRendererComponent(
            table, "Hello\n   world", false, false, 0, 1
        ) as JLabel

        assertEquals("Hello world", rendered.text)
        assertTrue(rendered.toolTipText == "Hello\n   world", "the tooltip keeps the value verbatim")
    }

    @Test
    fun `an empty locale cell is flagged, a filled one is not`() {
        // Missing and blank are different states and the panel colours them differently; what
        // matters here is that neither is left looking like an ordinary value.
        stubTranslations()
        val panel = TableViewPanel(project)
        val table = loadedTable(panel)
        val renderer = table.columnModel.getColumn(1).cellRenderer

        val missing = renderer.getTableCellRendererComponent(table, "", false, false, 0, 1).background
        val blank = renderer.getTableCellRendererComponent(table, "   ", false, false, 0, 1).background
        val filled = renderer.getTableCellRendererComponent(table, "Home", false, false, 0, 1).background

        assertEquals(table.background, filled, "a translated cell keeps the table background")
        assertTrue(missing != table.background, "a missing value must stand out")
        assertTrue(blank != table.background, "a blank value must stand out")
        assertTrue(missing != blank, "missing and blank are not the same state")
    }

    @Test
    fun `the usage column separates never scanned from orphan`() {
        stubTranslations()
        val panel = TableViewPanel(project)
        val table = loadedTable(panel)
        val renderer = table.columnModel.getColumn(3).cellRenderer

        val notScanned = renderer.getTableCellRendererComponent(table, "—", false, false, 0, 3) as JLabel
        val notScannedColor = notScanned.foreground
        val notScannedTip = notScanned.toolTipText

        val orphan = renderer.getTableCellRendererComponent(table, "0 (orphan)", false, false, 0, 3) as JLabel
        val orphanColor = orphan.foreground

        val used = renderer.getTableCellRendererComponent(table, "3", false, false, 0, 3).foreground

        assertNotNull(notScannedTip, "not-scanned explains itself in a tooltip")
        assertTrue(notScannedColor != orphanColor, "never scanned must not look like an orphan")
        assertTrue(orphanColor != used, "an orphan must not look like a used key")
    }
}
