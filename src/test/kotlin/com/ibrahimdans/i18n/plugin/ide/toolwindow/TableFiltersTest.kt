package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.ui.table.JBTable
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.awt.Container
import javax.swing.JComboBox

/** [TableFilters] on its own: what the two combos hold, what picking one does, what an empty table says. */
class TableFiltersTest : PlatformBaseTest() {

    private val notScanned = listOf(TranslationRow("a", mapOf("en" to "A")))
    private val scanned = listOf(TranslationRow("a", mapOf("en" to "A"), usageCount = 0))
    private var rows = notScanned
    private var changes = 0

    private fun filters() = TableFilters(TableViewModel(), { Config() }, { emptyList() }, { rows }) { changes++ }

    @Suppress("UNCHECKED_CAST")
    private fun namespaceCombo(filters: TableFilters) = combos(filters.bar)[0] as JComboBox<NamespaceFilter>

    @Suppress("UNCHECKED_CAST")
    private fun statusCombo(filters: TableFilters) = combos(filters.bar)[1] as JComboBox<StatusFilter>

    private fun combos(root: Container): List<JComboBox<*>> = root.components.flatMap {
        when (it) {
            is JComboBox<*> -> listOf(it)
            is Container -> combos(it)
            else -> emptyList()
        }
    }

    @Test
    fun `picking a status keeps it and filters again`() {
        val filters = filters()

        statusCombo(filters).selectedItem = StatusFilter.MISSING

        Assertions.assertEquals(StatusFilter.MISSING, filters.status)
        Assertions.assertEquals(1, changes)
    }

    @Test
    fun `Unused cannot be picked before a scan`() {
        val filters = filters()

        statusCombo(filters).selectedItem = StatusFilter.ORPHAN

        Assertions.assertEquals(StatusFilter.ALL, filters.status)
        Assertions.assertEquals(StatusFilter.ALL, statusCombo(filters).selectedItem)
    }

    @Test
    fun `Unused falls back to every key once the counts are gone`() {
        rows = scanned
        val filters = filters()
        statusCombo(filters).selectedItem = StatusFilter.ORPHAN
        Assertions.assertEquals(StatusFilter.ORPHAN, filters.status)

        rows = notScanned
        filters.resetUnavailableStatus()

        Assertions.assertEquals(StatusFilter.ALL, filters.status)
    }

    @Test
    fun `clear goes back to every namespace and every status`() {
        val filters = filters()
        filters.updateNamespaces(listOf(NamespaceFilter.All, NamespaceFilter.Named("auth")))
        namespaceCombo(filters).selectedItem = NamespaceFilter.Named("auth")
        statusCombo(filters).selectedItem = StatusFilter.BLANK

        filters.clear()

        Assertions.assertEquals(NamespaceFilter.All, filters.namespace)
        Assertions.assertEquals(StatusFilter.ALL, filters.status)
    }

    @Test
    fun `a namespace no longer offered is dropped`() {
        val filters = filters()
        filters.updateNamespaces(listOf(NamespaceFilter.All, NamespaceFilter.Named("auth")))
        namespaceCombo(filters).selectedItem = NamespaceFilter.Named("auth")

        filters.updateNamespaces(listOf(NamespaceFilter.All))

        Assertions.assertEquals(NamespaceFilter.All, filters.namespace)
    }

    @Test
    fun `without reviews the review filters are not offered`() {
        val filters = filters()

        filters.updateStatusEntries(TableReviews.OFF)

        Assertions.assertEquals(TableViewModel().statusFilters(TableReviews.OFF), filters.statusEntries())
    }

    @Test
    fun `an empty table says whether it loads, has no key, or matches no filter`() {
        val filters = filters()
        val emptyText = JBTable().emptyText

        filters.describeEmpty(emptyText, "", loading = true, hasRows = false) {}
        Assertions.assertEquals(PluginBundle.message("toolwindow.table.empty.loading"), emptyText.text)

        filters.describeEmpty(emptyText, "", loading = false, hasRows = false) {}
        Assertions.assertEquals(PluginBundle.message("toolwindow.table.empty.none"), emptyText.text)

        filters.describeEmpty(emptyText, " zz ", loading = false, hasRows = true) {}
        Assertions.assertEquals(PluginBundle.message("toolwindow.table.empty.query", "zz"), emptyText.text)
    }
}
