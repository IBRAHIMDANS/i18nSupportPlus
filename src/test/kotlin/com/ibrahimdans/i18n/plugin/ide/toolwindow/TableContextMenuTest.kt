package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.review.ui.ReviewCell
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.table.JBTable
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/** [TableContextMenu] against a host of its own: the cells a review applies to, the entries offered, the row actions. */
class TableContextMenuTest : PlatformBaseTest() {

    /** Key, en, fr, Usage; `a` has no French value. */
    private class Host(override val table: JTable) : TableMenuHost {
        override val keyColumn = 0
        override val usageColumn = 3
        override val locales = listOf("en", "fr")
        override val shownLocales = listOf("en", "fr")
        override val hiddenLocales = emptySet<String>()
        override val rows = emptyList<TranslationRow>()
        override val reviews = TableReviews.OFF
        override val reviewRoot: VirtualFile? = null
        var refreshed = 0
        override fun localeAt(column: Int): String? = shownLocales.getOrNull(column - 1)
        override fun sourceText(key: String): String = "source of $key"
        override fun toggleLocale(locale: String) = Unit
        override fun refresh() {
            refreshed++
        }
        override fun reloadReviews() = Unit
    }

    private fun host(): Host {
        val model = DefaultTableModel(
            arrayOf(arrayOf<Any>("a", "A", "", 0), arrayOf<Any>("b", "B", "Bf", 0)),
            arrayOf<Any>("Key", "en", "fr", "Usage")
        )
        return Host(JBTable(model).apply { cellSelectionEnabled = true })
    }

    private fun menu(host: Host) = TableContextMenu(project, null, TableViewModel(), host)

    @Test
    fun `a review applies to the selected locale cells holding a value`() {
        val host = host()
        host.table.setRowSelectionInterval(0, 1)
        host.table.setColumnSelectionInterval(0, 2)

        val cells = menu(host).selectedReviewCells()

        Assertions.assertEquals(
            listOf(
                ReviewCell("a", "en", "A", "source of a"),
                ReviewCell("b", "en", "B", "source of b"),
                ReviewCell("b", "fr", "Bf", "source of b"),
            ),
            cells
        )
    }

    @Test
    fun `without reviews the menu offers no review decision`() {
        Assertions.assertTrue(menu(host()).reviewMenuItems().isEmpty())
    }

    @Test
    fun `machine translation entries need a locale column and an opt-in`() {
        val menu = menu(host())

        Assertions.assertNull(menu.fillMenuItem(0), "the key column has no locale")
        Assertions.assertNull(menu.fillMenuItem(2), "machine translation is off")
        Assertions.assertNull(menu.retranslateMenuItem(2), "no reviews, no opt-in")
    }

    @Test
    fun `editing a locale cell starts its in-place editor`() {
        val host = host()
        host.table.changeSelection(1, 2, false, false)

        menu(host).editSelectedRow()

        Assertions.assertTrue(host.table.isEditing)
        Assertions.assertEquals(2, host.table.editingColumn)
        Assertions.assertEquals(0, host.refreshed, "no dialog, nothing to reload")
    }
}
