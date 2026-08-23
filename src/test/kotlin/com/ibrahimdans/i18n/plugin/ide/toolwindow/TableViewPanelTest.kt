package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
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
        mockkObject(TranslationDataLoader)
        every { TranslationDataLoader.loadAllTranslations(project, null) } returns mapOf(
            "en" to mapOf("menu.home" to "Home", "menu.about" to "About"),
            "fr" to mapOf("menu.home" to "Accueil", "menu.about" to "À propos"),
        )
        every { TranslationDataLoader.discoverLocales(project, null) } returns listOf("en", "fr")

        val panel = TableViewPanel(project)
        val table = find(panel, JTable::class.java)!!

        // refresh() loads on a pooled thread and rebuilds through invokeLater. The test body
        // runs on the EDT, so the posted rebuild only happens while we pump the queue.
        panel.refresh()
        val deadline = System.currentTimeMillis() + 15_000
        while (table.columnCount == 0 && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }

        assertEquals(4, table.columnCount, "Key + en + fr + Usage")
        assertEquals(PluginBundle.message("toolwindow.table.column.key"), table.getColumnName(0))
        assertEquals("en", table.getColumnName(1))
        assertEquals("fr", table.getColumnName(2))
        assertEquals(PluginBundle.message("toolwindow.table.column.usage"), table.getColumnName(3))

        assertEquals(2, table.rowCount)
        // Nothing has been scanned yet, so every row reads as not scanned rather than as zero —
        // the difference between "no usage found" and "never looked".
        for (row in 0 until table.rowCount) {
            // assertEquals(expected, actual, message) is NOT usable when the expected value is a
            // String: BasePlatformTestCase inherits junit.framework's assertEquals(message,
            // expected, actual), whose first parameter is also a String, and it wins the overload.
            assertTrue(table.getValueAt(row, 3) == "—", "an unscanned row must not read as an orphan")
        }
    }
}
