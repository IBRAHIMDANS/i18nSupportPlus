package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.ide.util.PropertiesComponent
// PlatformBaseTest inherits junit.framework.TestCase, whose assertNull(message, value) is a member
// and outranks an import when both arguments are Strings — such calls go through `Assertions.`.
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [ToolWindowViewState] and for the tab restore it drives in [ShellContent].
 *
 * The light project — and so its [PropertiesComponent] — outlives a single test: every test
 * writes only under its own module names, and the project scope is reset around each one.
 */
class ToolWindowViewStateTest : PlatformBaseTest() {

    private val state: ToolWindowViewState
        get() = ToolWindowViewState.getInstance(project)

    override fun setUp() {
        super.setUp()
        resetProjectScope()
    }

    override fun tearDown() {
        try {
            resetProjectScope()
        } finally {
            super.tearDown()
        }
    }

    /** Writing the defaults removes the entries. */
    private fun resetProjectScope() {
        state.setActiveTab(null, ToolWindowTab.TREE)
        state.setHiddenLocales(null, emptySet())
    }

    private fun shell(modules: List<ModuleConfig> = emptyList()) = ShellContent(
        project,
        ShellDiagnostics(emptyList(), sourceCount = 1, modules = modules),
        onRunWizard = {},
        onOpenSettings = {},
        viewState = state
    )

    // -----------------------------------------------------------------------
    // Defaults
    // -----------------------------------------------------------------------

    @Test
    fun `nothing saved gives the current defaults`() {
        val module = ModuleConfig(name = "defaults-module")

        assertEquals(ToolWindowTab.TREE, state.activeTab(null), "The tree has always been the first view")
        assertEquals(ToolWindowTab.TREE, state.activeTab(module))
        assertTrue(state.hiddenLocales(null).isEmpty(), "Every locale is shown until one is hidden")
        assertTrue(state.hiddenLocales(module).isEmpty())
    }

    @Test
    fun `an unknown saved tab falls back to the default`() {
        PropertiesComponent.getInstance(project).setValue("com.ibrahimdans.i18n.toolWindow.activeTab", "GRAPH")

        assertEquals(ToolWindowTab.TREE, state.activeTab(null))
    }

    // -----------------------------------------------------------------------
    // Round trip
    // -----------------------------------------------------------------------

    @Test
    fun `the active tab is read back as written`() {
        state.setActiveTab(null, ToolWindowTab.STATS)
        assertEquals(ToolWindowTab.STATS, state.activeTab(null))

        val module = ModuleConfig(name = "round-trip-tab")
        state.setActiveTab(module, ToolWindowTab.TABLE)
        assertEquals(ToolWindowTab.TABLE, state.activeTab(module))
    }

    @Test
    fun `the hidden locales are read back as written`() {
        state.setHiddenLocales(null, setOf("fr", "de"))
        assertEquals(setOf("de", "fr"), state.hiddenLocales(null))

        val module = ModuleConfig(name = "round-trip-locales")
        state.setHiddenLocales(module, setOf("es"))
        assertEquals(setOf("es"), state.hiddenLocales(module))
    }

    @Test
    fun `writing the default forgets the value`() {
        state.setHiddenLocales(null, setOf("fr"))
        state.setHiddenLocales(null, emptySet())
        assertTrue(state.hiddenLocales(null).isEmpty(), "Showing every locale again must stick")

        state.setActiveTab(null, ToolWindowTab.TABLE)
        state.setActiveTab(null, ToolWindowTab.TREE)
        assertEquals(ToolWindowTab.TREE, state.activeTab(null))
    }

    @Test
    fun `a fresh instance reads what another one wrote`() {
        ToolWindowViewState.getInstance(project).setHiddenLocales(null, setOf("it"))

        assertEquals(setOf("it"), ToolWindowViewState.getInstance(project).hiddenLocales(null))
    }

    // -----------------------------------------------------------------------
    // Isolation
    // -----------------------------------------------------------------------

    @Test
    fun `two modules do not share their values`() {
        val frontend = ModuleConfig(name = "isolation-frontend")
        val backend = ModuleConfig(name = "isolation-backend")

        state.setHiddenLocales(frontend, setOf("de"))
        state.setActiveTab(frontend, ToolWindowTab.STATS)

        assertTrue(state.hiddenLocales(backend).isEmpty(), "Hiding a locale in one module must not hide it in another")
        assertEquals(ToolWindowTab.TREE, state.activeTab(backend))
        assertTrue(state.hiddenLocales(null).isEmpty(), "Nor in the project scope")
        assertEquals(ToolWindowTab.TREE, state.activeTab(null))
    }

    @Test
    fun `an unnamed module is told apart by its root`() {
        val first = ModuleConfig(name = "", rootDirectory = "isolation/first")
        val second = ModuleConfig(name = "", rootDirectory = "isolation/second")

        state.setHiddenLocales(first, setOf("pt"))

        assertEquals(setOf("pt"), state.hiddenLocales(first))
        assertTrue(state.hiddenLocales(second).isEmpty())
    }

    @Test
    fun `two modules of the same name are told apart by their root`() {
        val web = ModuleConfig(name = "homonym", rootDirectory = "apps/web")
        val admin = ModuleConfig(name = "homonym", rootDirectory = "apps/admin")

        state.setHiddenLocales(web, setOf("de"))
        state.setActiveTab(web, ToolWindowTab.STATS)

        assertEquals(setOf("de"), state.hiddenLocales(web))
        assertTrue(state.hiddenLocales(admin).isEmpty(), "A homonym must not share the hidden locales")
        assertEquals(ToolWindowTab.TREE, state.activeTab(admin))
    }

    // -----------------------------------------------------------------------
    // Shell wiring
    // -----------------------------------------------------------------------

    @Test
    fun `the shell opens on the saved tab`() {
        state.setActiveTab(null, ToolWindowTab.TABLE)

        assertEquals(ToolWindowTab.TABLE.ordinal, shell().tabs!!.selectedIndex)
    }

    @Test
    fun `selecting a tab saves it`() {
        val tabs = shell().tabs!!

        tabs.selectedIndex = ToolWindowTab.STATS.ordinal

        assertEquals(ToolWindowTab.STATS, state.activeTab(null))
    }

    @Test
    fun `building the shell writes nothing`() {
        shell()

        Assertions.assertNull(
            PropertiesComponent.getInstance(project).getValue("com.ibrahimdans.i18n.toolWindow.activeTab"),
            "Opening the tool window is not a choice of tab"
        )
    }

    @Test
    fun `the saved tab is shared by every module`() {
        val modules = listOf(ModuleConfig(name = "shared-frontend"), ModuleConfig(name = "shared-backend"))
        state.setActiveTab(null, ToolWindowTab.STATS)
        val built = shell(modules)

        built.showModule(1)

        assertEquals(ToolWindowTab.STATS.ordinal, built.tabs!!.selectedIndex)
        assertEquals(ToolWindowTab.STATS, state.activeTab(null))
    }

    // -----------------------------------------------------------------------
    // Module rename
    // -----------------------------------------------------------------------

    @Test
    fun `a renamed module keeps its tab and hidden locales`() {
        val before = ModuleConfig(name = "rename-before", rootDirectory = "apps/web")
        val after = before.copy(name = "rename-after")
        state.setActiveTab(before, ToolWindowTab.TABLE)
        state.setHiddenLocales(before, setOf("de", "ja"))

        state.moveModuleState(before, after)

        assertEquals(ToolWindowTab.TABLE, state.activeTab(after))
        assertEquals(setOf("de", "ja"), state.hiddenLocales(after))
        assertEquals(ToolWindowTab.TREE, state.activeTab(before), "The old entry is removed")
        assertTrue(state.hiddenLocales(before).isEmpty(), "The old entry is removed")
        state.moveModuleState(after, before)
    }

    @Test
    fun `an unnamed module that gets a name keeps its state`() {
        val unnamed = ModuleConfig(rootDirectory = "rename-unnamed-root")
        val named = unnamed.copy(name = "rename-now-named")
        state.setHiddenLocales(unnamed, setOf("fr"))

        state.moveModuleState(unnamed, named)

        assertEquals(setOf("fr"), state.hiddenLocales(named))
        assertTrue(state.hiddenLocales(unnamed).isEmpty())
        state.setHiddenLocales(named, emptySet())
    }

    @Test
    fun `a move never overwrites another module's state`() {
        val moved = ModuleConfig(name = "rename-moved")
        val taken = ModuleConfig(name = "rename-taken")
        state.setHiddenLocales(moved, setOf("de"))
        state.setHiddenLocales(taken, setOf("es"))

        state.moveModuleState(moved, taken)

        assertEquals(setOf("es"), state.hiddenLocales(taken))
        assertEquals(setOf("de"), state.hiddenLocales(moved), "Left where it was rather than lost")
        state.setHiddenLocales(moved, emptySet())
        state.setHiddenLocales(taken, emptySet())
    }

    @Test
    fun `a rename is told apart from a removal that shifts the list`() {
        val web = ModuleConfig(name = "web", rootDirectory = "apps/web")
        val api = ModuleConfig(name = "api", rootDirectory = "apps/api")
        val docs = ModuleConfig(name = "docs", rootDirectory = "apps/docs")

        assertEquals(
            listOf(web to web.copy(name = "frontend")),
            ToolWindowViewState.renamedModules(listOf(web, api), listOf(web.copy(name = "frontend"), api))
        )
        val unnamed = ModuleConfig(rootDirectory = "apps/admin")
        assertEquals(
            listOf(unnamed to unnamed.copy(name = "admin")),
            ToolWindowViewState.renamedModules(listOf(unnamed), listOf(unnamed.copy(name = "admin")))
        )
        // `web` removed: `api` and `docs` shift up, a new module is appended. Nothing is renamed.
        assertTrue(
            ToolWindowViewState.renamedModules(listOf(web, api, docs), listOf(api, docs, ModuleConfig(name = "new"))).isEmpty()
        )
        // Unchanged modules, or a change that keeps the scope, move nothing.
        assertTrue(ToolWindowViewState.renamedModules(listOf(web), listOf(web.copy(preset = "vue-i18n"))).isEmpty())
    }
}
