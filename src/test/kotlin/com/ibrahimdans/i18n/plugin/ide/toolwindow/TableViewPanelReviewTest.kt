package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import javax.swing.JLabel
import javax.swing.JTable

/** The table where the module's reviews are on, and where they are not. */
class TableViewPanelReviewTest : PlatformBaseTest() {

    @AfterEach
    fun tearDownMocks() = unmockkAll()

    private val defaultNs: String get() = Settings.getInstance(project).config().defaultNamespaces().first()

    private fun stubTranslations() {
        mockkObject(TranslationDataLoader)
        every { TranslationDataLoader.loadAllTranslations(project, null) } returns mapOf(
            "menu.home" to mapOf("en" to "Home", "fr" to "Accueil"),
            "menu.about" to mapOf("en" to "About", "fr" to "À propos"),
        )
        every { TranslationDataLoader.discoverLocales(project, null) } returns listOf("en", "fr")
    }

    private fun root(enabled: Boolean): VirtualFile {
        val root = myFixture.tempDirFixture.findOrCreateDir("app")
        val store = ReviewStore.getInstance(project).apply { reviewer = { "Alice" } }
        if (enabled) WriteCommandAction.runWriteCommandAction(project) {
            store.enable(root)
            store.decide(root, "fr", ReviewKey(defaultNs, "menu.home"), ReviewState.APPROVED, "Accueil")
            store.decide(root, "fr", ReviewKey(defaultNs, "menu.about"), ReviewState.APPROVED, "A propos")
        }
        return root
    }

    private fun panel(root: VirtualFile): Pair<TableViewPanel, JTable> {
        stubTranslations()
        val panel = TableViewPanel(project).apply { resolveReviewRoot = { root } }
        val table = find(panel)
        panel.refresh()
        val deadline = System.currentTimeMillis() + 15_000
        while (table.columnCount == 0 && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        return panel to table
    }

    private fun find(root: Container): JTable {
        val queue = ArrayDeque<Container>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            next.components.forEach { child ->
                if (child is JTable) return child
                if (child is Container) queue.add(child)
            }
        }
        error("no table")
    }

    private fun select(table: JTable, rows: IntRange, columns: IntRange) {
        table.setRowSelectionInterval(rows.first, rows.last)
        table.setColumnSelectionInterval(columns.first, columns.last)
    }

    @Test
    fun withoutReviewsFolderTheColumnsTheFiltersAndTheMenuAreUnchanged() {
        val (panel, table) = panel(root(enabled = false))

        assertEquals(4, table.columnCount, "Key + en + fr + Usage")
        assertEquals(viewModelFilters(), panel.statusFilterEntries())
        select(table, 0..1, 1..2)
        assertTrue(panel.reviewMenuItems().isEmpty(), "no review entry without a reviews folder")
        val cell = table.columnModel.getColumn(2).cellRenderer.getTableCellRendererComponent(table, "Accueil", false, false, 1, 2) as JLabel
        assertNull(cell.icon)
        assertEquals("Accueil", cell.toolTipText)
    }

    private fun viewModelFilters() =
        listOf(StatusFilter.ALL, StatusFilter.MISSING, StatusFilter.BLANK, StatusFilter.ORPHAN, StatusFilter.DUPLICATE)

    @Test
    fun withReviewsTheCellsTheFiltersAndTheMenuShowThem() {
        val (panel, table) = panel(root(enabled = true))

        assertEquals(4, table.columnCount, "reviews add no column")
        assertEquals(StatusFilter.entries.toList(), panel.statusFilterEntries())

        // Sorted by key: row 0 is menu.about (approved on "A propos", holds "À propos"), row 1 menu.home.
        val renderer = table.columnModel.getColumn(2).cellRenderer
        val approved = renderer.getTableCellRendererComponent(table, "Accueil", false, false, 1, 2) as JLabel
        assertSame(AllIcons.General.InspectionsOK, approved.icon)
        assertTrue(approved.toolTipText.contains(ReviewBadge.label(ShownReview.APPROVED)), approved.toolTipText)
        assertTrue(approved.toolTipText.contains("Alice"), approved.toolTipText)

        val changed = renderer.getTableCellRendererComponent(table, "À propos", false, false, 0, 2) as JLabel
        assertSame(AllIcons.Actions.Show, changed.icon)
        assertTrue(changed.toolTipText.contains(ReviewBadge.label(ShownReview.CHANGED_SINCE_APPROVAL)), changed.toolTipText)

        val unreviewed = table.columnModel.getColumn(1).cellRenderer.getTableCellRendererComponent(table, "Home", false, false, 1, 1) as JLabel
        assertNull(unreviewed.icon, "the shared renderer must not leak the previous cell's icon")

        // Two rows × en and fr: four cells, one decision.
        select(table, 0..1, 1..2)
        val items = panel.reviewMenuItems()
        assertEquals(
            listOf(
                PluginBundle.message("review.action.approve.many", 4),
                PluginBundle.message("review.action.request.changes"),
                PluginBundle.message("review.action.mark"),
                PluginBundle.message("review.action.comments"),
            ),
            items.map { it.text }
        )
        assertTrue(items[0].isEnabled)
        assertFalse(items[3].isEnabled, "the thread of four cells cannot be one dialog")
        assertEquals(4, panel.selectedReviewCells().size)
    }

    @Test
    fun theKeyColumnAloneOffersNoCellToDecideOn() {
        val (panel, table) = panel(root(enabled = true))

        select(table, 0..1, 0..0)

        assertTrue(panel.selectedReviewCells().isEmpty())
        assertTrue(panel.reviewMenuItems().none { it.isEnabled })
    }

    @Test
    fun approvingFromTheTableIsReadBackIntoTheToReviewFilter() {
        val root = root(enabled = true)
        val (panel, table) = panel(root)
        assertEquals(2, table.rowCount)

        // menu.about was approved on an older value: it is the one key to review.
        val combo = findCombo(panel)
        combo.selectedItem = StatusFilter.TO_REVIEW
        assertEquals(1, table.rowCount)
        assertEquals("menu.about", table.getValueAt(0, 0))

        select(table, 0..0, 2..2)
        panel.reviewMenuItems()[0].doClick()
        panel.reloadReviews()

        assertEquals(0, table.rowCount, "approved on its value, the key leaves the To review filter")
        assertEquals(ReviewState.APPROVED, ReviewStore.getInstance(project).reviews(root, "fr").getValue(ReviewKey(defaultNs, "menu.about")).state)
    }

    @Suppress("UNCHECKED_CAST")
    private fun findCombo(root: Container): javax.swing.JComboBox<StatusFilter> {
        val queue = ArrayDeque<Container>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            next.components.forEach { child ->
                if (child is javax.swing.JComboBox<*> && child.getItemAt(0) is StatusFilter) return child as javax.swing.JComboBox<StatusFilter>
                if (child is Container) queue.add(child)
            }
        }
        error("no status combo")
    }
}
