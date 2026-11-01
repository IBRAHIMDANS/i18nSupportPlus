package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** [ModuleSelector]: when it shows, what it lists, and which choice switches the views. */
class ModuleSelectorTest : PlatformBaseTest() {

    private val shown = mutableListOf<Int>()

    private fun diagnostics(vararg names: String) =
        ShellDiagnostics(searchedPaths = emptyList(), sourceCount = 2, modules = names.map { ModuleConfig(name = it) })

    @Test
    fun `several modules are listed, and listing them switches nothing`() {
        val selector = ModuleSelector(project) { shown += it }

        selector.update(diagnostics("web", "admin"))

        Assertions.assertTrue(selector.component.isVisible)
        Assertions.assertEquals(2, selector.component.itemCount)
        Assertions.assertEquals(0, selector.component.selectedIndex)
        Assertions.assertTrue(shown.isEmpty(), "filling the list is not a choice")
    }

    @Test
    fun `choosing a module shows its views`() {
        val selector = ModuleSelector(project) { shown += it }
        selector.update(diagnostics("web", "admin"))

        selector.component.selectedIndex = 1

        Assertions.assertEquals(listOf(1), shown)
    }

    @Test
    fun `a single module hides the selector`() {
        val selector = ModuleSelector(project) { shown += it }

        selector.update(diagnostics("web"))

        Assertions.assertFalse(selector.component.isVisible)
        Assertions.assertEquals(0, selector.component.itemCount)
    }
}
