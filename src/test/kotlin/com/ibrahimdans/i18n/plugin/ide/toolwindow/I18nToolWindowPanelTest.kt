package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBTabbedPane
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import javax.swing.JLabel

/**
 * Tests for the tool window shell.
 *
 * [I18nToolWindowPanel] itself cannot be built here: its constructor reaches for
 * `ActionManager`, which the headless light container used by `BasePlatformTestCase` does not
 * provide — the two smoke tests that build it are still named `ignoredTest…` for that reason.
 * Everything the shell decides therefore lives outside it, in [ShellDiagnostics],
 * [shellNeedsRebuild], [SearchResults], [SearchDebouncer] and [ShellContent], and this class
 * covers those.
 */
class I18nToolWindowPanelTest : PlatformBaseTest() {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Every descendant of [root] of type [type], the root's own children included. */
    private fun <T> findAll(root: Container, type: Class<T>): List<T> {
        val found = mutableListOf<T>()
        val queue = ArrayDeque<Container>().apply { add(root) }
        while (queue.isNotEmpty()) {
            for (child in queue.removeFirst().components) {
                if (type.isInstance(child)) found.add(type.cast(child))
                if (child is Container) queue.add(child)
            }
        }
        return found
    }

    private fun diagnostics(
        searchedPaths: List<String> = emptyList(),
        sourceCount: Int = 1,
        modules: List<ModuleConfig> = emptyList()
    ) = ShellDiagnostics(searchedPaths, sourceCount, modules)

    private fun content(diagnostics: ShellDiagnostics) =
        ShellContent(project, diagnostics, onRunWizard = {}, onOpenSettings = {})

    /** A runner that keeps the pending action instead of timing it, like a restarted timer. */
    private class ManualRunner : DelayedRunner {
        var lastDelayMs = -1
        private var pending: (() -> Unit)? = null

        override fun schedule(delayMs: Int, action: () -> Unit) {
            lastDelayMs = delayMs
            pending = action
        }

        fun fire() {
            val action = pending
            pending = null
            action?.invoke()
        }
    }

    // -----------------------------------------------------------------------
    // Layout decision
    // -----------------------------------------------------------------------

    @Test
    fun `no resolved root gives the empty state, whatever is configured`() {
        assertEquals(ShellLayout.EMPTY, diagnostics(sourceCount = 0).layout)
        assertEquals(
            ShellLayout.EMPTY,
            diagnostics(
                searchedPaths = listOf("public/locales"),
                sourceCount = 0,
                modules = listOf(ModuleConfig(name = "frontend", rootDirectory = "public/locales"))
            ).layout,
            "A configured module the scan resolves to nothing is still an empty project"
        )
    }

    @Test
    fun `one module or none keeps the single-level layout`() {
        assertEquals(ShellLayout.SINGLE, diagnostics(sourceCount = 3).layout)
        assertEquals(
            ShellLayout.SINGLE,
            diagnostics(sourceCount = 3, modules = listOf(ModuleConfig(name = "only"))).layout
        )
    }

    @Test
    fun `two modules switch to the module selector`() {
        val two = listOf(ModuleConfig(name = "frontend"), ModuleConfig(name = "backend"))
        assertEquals(ShellLayout.MULTI, diagnostics(sourceCount = 3, modules = two).layout)
    }

    @Test
    fun `an unnamed module still gets a label in the selector`() {
        val names = diagnostics(modules = listOf(ModuleConfig(name = ""), ModuleConfig(name = "admin"))).moduleNames
        assertEquals(listOf("Module", "admin"), names)
    }

    // -----------------------------------------------------------------------
    // Where the plugin looked
    // -----------------------------------------------------------------------

    @Test
    fun `the searched paths gather the module roots and the global one`() {
        val config = Config(
            translationsRoot = "src/i18n/",
            modules = listOf(
                ModuleConfig(name = "frontend", rootDirectory = "public/locales"),
                ModuleConfig(name = "admin", rootDirectory = "  "),
                ModuleConfig(name = "duplicate", rootDirectory = "public/locales")
            )
        )
        assertEquals(listOf("public/locales", "src/i18n"), ShellDiagnostics.searchedPaths(config))
    }

    @Test
    fun `nothing configured means nothing to list`() {
        assertTrue(ShellDiagnostics.searchedPaths(Config()).isEmpty())
    }

    // -----------------------------------------------------------------------
    // Rebuild decision
    // -----------------------------------------------------------------------

    @Test
    fun `the content is rebuilt only when the shell itself changes`() {
        val before = diagnostics(sourceCount = 3)

        assertTrue(shellNeedsRebuild(null, before), "The first pass has nothing to keep")
        assertFalse(
            shellNeedsRebuild(before, diagnostics(sourceCount = 12)),
            "More files in the same layout must not reset the selected tab"
        )
        assertTrue(
            shellNeedsRebuild(before, diagnostics(sourceCount = 0)),
            "Losing every source must bring the empty state up"
        )
        assertTrue(
            shellNeedsRebuild(before, diagnostics(sourceCount = 3, modules = listOf(ModuleConfig("a"), ModuleConfig("b")))),
            "A new module changes the selector's contents"
        )
    }

    @Test
    fun `the empty state follows the diagnostics it prints`() {
        val before = diagnostics(searchedPaths = listOf("locales"), sourceCount = 0)
        val after = diagnostics(searchedPaths = listOf("public/locales"), sourceCount = 0)
        assertTrue(shellNeedsRebuild(before, after), "The empty state names the paths, so it must follow them")
        assertFalse(shellNeedsRebuild(before, before.copy()))
    }

    // -----------------------------------------------------------------------
    // Search result count
    // -----------------------------------------------------------------------

    @Test
    fun `the count matches keys and values, case-insensitively`() {
        val translations = mapOf(
            "menu.home" to mapOf("en" to "Home", "fr" to "Accueil"),
            "menu.about" to mapOf("en" to "About", "fr" to "À propos"),
            "footer.legal" to mapOf("en" to "Legal", "fr" to "Mentions légales")
        )
        assertEquals(2, SearchResults.count(translations, "MENU"), "The key carries the match")
        assertEquals(1, SearchResults.count(translations, "accueil"), "A value carries it too")
        assertEquals(0, SearchResults.count(translations, "nothing here"))
        assertEquals(3, SearchResults.count(translations, "   "), "A blank query filters nothing")
    }

    @Test
    fun `the count is labelled only once something is searched`() {
        assertEquals("", SearchResults.label(3, ""), "No question, no answer")
        assertTrue(SearchResults.label(0, "menu").isNotBlank(), "No result must read differently from no search")
        assertTrue(SearchResults.label(1, "menu").contains("1"))
        assertTrue(SearchResults.label(7, "menu").contains("7"))
    }

    // -----------------------------------------------------------------------
    // Search debounce
    // -----------------------------------------------------------------------

    @Test
    fun `a burst of keystrokes costs a single filter pass`() {
        val runner = ManualRunner()
        val applied = mutableListOf<String>()
        val debouncer = SearchDebouncer(runner) { applied.add(it) }

        debouncer.onQueryChanged("m")
        debouncer.onQueryChanged("me")
        debouncer.onQueryChanged("men")
        assertTrue(applied.isEmpty(), "Nothing may be filtered before the delay expires")

        runner.fire()
        assertEquals(listOf("men"), applied, "Only the last query of the burst survives")
        assertEquals(SEARCH_DELAY_MS, runner.lastDelayMs)
    }

    // -----------------------------------------------------------------------
    // Content — built headless, without the panel's ActionManager
    // -----------------------------------------------------------------------

    @Test
    fun `no root at all gives an empty state instead of tabs`() {
        val built = content(diagnostics(searchedPaths = listOf("public/locales"), sourceCount = 0))

        assertNull(built.tabs, "An unconfigured project has nothing to put in a tab")
        assertTrue(
            findAll(built.component, JBTabbedPane::class.java).isEmpty(),
            "The empty state must not carry a tabbed pane"
        )
        assertEquals(
            2,
            findAll(built.component, HyperlinkLabel::class.java).size,
            "The empty state offers the wizard and the settings, and nothing else"
        )
        assertNull(built.activeModule, "There is no module to scope anything to")
    }

    @Test
    fun `the empty state says where the plugin looked`() {
        val configured = content(diagnostics(searchedPaths = listOf("public/locales"), sourceCount = 0))
        assertTrue(
            findAll(configured.component, JLabel::class.java).any { it.text.orEmpty().contains("public/locales") },
            "The user must be able to see which directory was scanned"
        )

        val bare = content(diagnostics(sourceCount = 0))
        assertTrue(
            findAll(bare.component, JLabel::class.java).any { it.text.orEmpty().isNotBlank() },
            "With no root configured the empty state still has something to say"
        )
    }

    @Test
    fun `two modules give a single tabbed pane, not one nested in another`() {
        val modules = listOf(
            ModuleConfig(name = "frontend", rootDirectory = "frontend/locales"),
            ModuleConfig(name = "backend", rootDirectory = "backend/i18n")
        )
        val built = content(diagnostics(sourceCount = 4, modules = modules))

        assertNotNull(built.tabs)
        assertTrue(built.component is JBTabbedPane, "The content is the tab pane itself")
        assertTrue(
            findAll(built.component, JBTabbedPane::class.java).isEmpty(),
            "One level of tabs: no second pane may nest inside the first"
        )
        assertEquals(3, built.tabs!!.tabCount, "Tree, table and statistics — one level, three tabs")
        assertEquals("frontend", built.activeModule?.name, "The first module is shown to begin with")
    }

    @Test
    fun `switching module keeps the selected view`() {
        val modules = listOf(ModuleConfig(name = "frontend"), ModuleConfig(name = "backend"))
        val built = content(diagnostics(sourceCount = 4, modules = modules))
        val tabs = built.tabs!!

        tabs.selectedIndex = 2
        built.showModule(1)

        assertEquals(2, tabs.selectedIndex, "The view outlives the module switch — it used to be lost")
        assertEquals("backend", built.activeModule?.name)
        assertEquals(3, tabs.tabCount, "Switching swaps the components, it does not add tabs")
    }

    // -----------------------------------------------------------------------
    // Smoke — the panel itself, still out of reach of the headless container
    // -----------------------------------------------------------------------

    /**
     * Smoke test: the panel instantiates without exception when no module is configured.
     *
     * Named ignoredTest because ActionManager.getInstance() is not available in the headless
     * light test container used by BasePlatformTestCase.
     */
    fun ignoredTestSingleLevelPanelInstantiatesWithoutException() {
        val settings = Settings.getInstance(project)
        val original = settings.config()
        settings.modules.clear()
        try {
            assertNotNull(I18nToolWindowPanel(project), "Panel should be created in single-level mode")
        } finally {
            settings.setConfig(original)
        }
    }

    /**
     * Smoke test: the panel instantiates without exception when 2 modules are configured.
     * Renamed ignoredTest for the same reason as above.
     */
    fun ignoredTestMultiModulePanelInstantiatesWithTwoModules() {
        val settings = Settings.getInstance(project)
        val original = settings.config()
        settings.modules.clear()
        settings.modules.add(ModuleConfig(name = "frontend", rootDirectory = "frontend/locales"))
        settings.modules.add(ModuleConfig(name = "backend", rootDirectory = "backend/i18n"))
        try {
            assertNotNull(I18nToolWindowPanel(project), "Panel should be created in multi-module mode")
        } finally {
            settings.setConfig(original)
        }
    }

    // -----------------------------------------------------------------------
    // Settings interaction — no Swing required
    // -----------------------------------------------------------------------

    /**
     * Verifies that Settings correctly stores the module configurations the shell reads to
     * choose between the single-level layout and the module selector.
     */
    @Test
    fun `settings returns correct module count for the layout decision`() {
        val settings = Settings.getInstance(project)
        val original = settings.config()
        try {
            settings.modules.clear()
            assertEquals(0, settings.modules.size, "No modules → single-level layout")

            settings.modules.add(ModuleConfig(name = "only", rootDirectory = "locales"))
            assertEquals(1, settings.modules.size, "1 module → still single-level layout")

            settings.modules.add(ModuleConfig(name = "second", rootDirectory = "i18n"))
            assertEquals(2, settings.modules.size, "2 modules → the module selector appears")
        } finally {
            settings.setConfig(original)
        }
    }

    /**
     * Validates that module names are preserved in Settings, as [ShellDiagnostics.moduleNames]
     * turns them into the entries of the toolbar's module selector.
     */
    @Test
    fun `module names are preserved for the selector`() {
        val settings = Settings.getInstance(project)
        val original = settings.config()
        try {
            settings.modules.clear()
            settings.modules.add(ModuleConfig(name = "frontend", rootDirectory = "public/locales"))
            settings.modules.add(ModuleConfig(name = "admin", rootDirectory = "admin/i18n"))

            assertEquals("frontend", settings.modules[0].name)
            assertEquals("admin", settings.modules[1].name)
        } finally {
            settings.setConfig(original)
        }
    }
}
