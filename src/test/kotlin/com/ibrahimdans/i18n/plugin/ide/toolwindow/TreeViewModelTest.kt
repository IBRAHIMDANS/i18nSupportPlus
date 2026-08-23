package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TreeViewModelTest {

    private val project = mockk<Project>()
    private val viewModel = TreeViewModel()

    @BeforeEach
    fun setUp() {
        mockkObject(TranslationDataLoader)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(TranslationDataLoader)
    }

    @Test
    fun `loadTranslations returns empty root when no translations exist`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns emptyMap()

        val root = viewModel.loadTranslations(project)

        assertEquals("root", root.key)
        assertEquals("", root.fullPath)
        assertTrue(root.children.isEmpty())
    }

    @Test
    fun `loadTranslations builds single-level keys`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "greeting" to mapOf("en" to "Hello", "fr" to "Bonjour"),
            "farewell" to mapOf("en" to "Goodbye", "fr" to "Au revoir")
        )

        val root = viewModel.loadTranslations(project)

        assertEquals(2, root.children.size)
        assertTrue(root.children.containsKey("greeting"))
        assertTrue(root.children.containsKey("farewell"))

        val greeting = root.children["greeting"]!!
        assertTrue(greeting.isLeaf)
        assertEquals("greeting", greeting.fullPath)
        assertEquals("Hello", greeting.values["en"])
        assertEquals("Bonjour", greeting.values["fr"])
    }

    @Test
    fun `loadTranslations builds nested hierarchical tree`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "common.buttons.save" to mapOf("en" to "Save"),
            "common.buttons.cancel" to mapOf("en" to "Cancel"),
            "common.title" to mapOf("en" to "App")
        )

        val root = viewModel.loadTranslations(project)

        // root -> common -> buttons -> save, cancel
        //                -> title
        val common = root.children["common"]!!
        assertFalse(common.isLeaf)
        assertEquals("common", common.fullPath)
        assertEquals(2, common.children.size)

        val buttons = common.children["buttons"]!!
        assertFalse(buttons.isLeaf)
        assertEquals("common.buttons", buttons.fullPath)
        assertEquals(2, buttons.children.size)

        val save = buttons.children["save"]!!
        assertTrue(save.isLeaf)
        assertEquals("common.buttons.save", save.fullPath)
        assertEquals("Save", save.values["en"])

        val cancel = buttons.children["cancel"]!!
        assertTrue(cancel.isLeaf)
        assertEquals("common.buttons.cancel", cancel.fullPath)
        assertEquals("Cancel", cancel.values["en"])

        val title = common.children["title"]!!
        assertTrue(title.isLeaf)
        assertEquals("common.title", title.fullPath)
        assertEquals("App", title.values["en"])
    }

    @Test
    fun `loadTranslations preserves multiple locale values on leaf nodes`() {
        every { TranslationDataLoader.loadAllTranslations(project) } returns mapOf(
            "nav.home" to mapOf("en" to "Home", "fr" to "Accueil", "de" to "Startseite")
        )

        val root = viewModel.loadTranslations(project)
        val home = root.children["nav"]!!.children["home"]!!

        assertEquals(3, home.values.size)
        assertEquals("Home", home.values["en"])
        assertEquals("Accueil", home.values["fr"])
        assertEquals("Startseite", home.values["de"])
    }

    // --- getMissingKeys tests ---

    @Test
    fun `getMissingKeys returns empty set when all locales are present`() {
        val root = TranslationNode(
            key = "root", fullPath = "", values = emptyMap(),
            children = mutableMapOf(
                "hello" to TranslationNode(
                    key = "hello", fullPath = "hello",
                    values = mapOf("en" to "Hello", "fr" to "Bonjour"),
                    isLeaf = true
                )
            )
        )

        val missing = viewModel.getMissingKeys(root, listOf("en", "fr"))
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `getMissingKeys detects key missing in one locale`() {
        val root = TranslationNode(
            key = "root", fullPath = "", values = emptyMap(),
            children = mutableMapOf(
                "hello" to TranslationNode(
                    key = "hello", fullPath = "hello",
                    values = mapOf("en" to "Hello"),
                    isLeaf = true
                )
            )
        )

        val missing = viewModel.getMissingKeys(root, listOf("en", "fr"))
        assertEquals(setOf("hello"), missing)
    }

    @Test
    fun `getMissingKeys detects multiple missing keys in nested tree`() {
        val root = TranslationNode(
            key = "root", fullPath = "", values = emptyMap(),
            children = mutableMapOf(
                "common" to TranslationNode(
                    key = "common", fullPath = "common", values = emptyMap(),
                    children = mutableMapOf(
                        "save" to TranslationNode(
                            key = "save", fullPath = "common.save",
                            values = mapOf("en" to "Save", "fr" to "Sauvegarder"),
                            isLeaf = true
                        ),
                        "delete" to TranslationNode(
                            key = "delete", fullPath = "common.delete",
                            values = mapOf("en" to "Delete"),
                            isLeaf = true
                        )
                    )
                ),
                "title" to TranslationNode(
                    key = "title", fullPath = "title",
                    values = mapOf("fr" to "Titre"),
                    isLeaf = true
                )
            )
        )

        val missing = viewModel.getMissingKeys(root, listOf("en", "fr"))
        assertEquals(setOf("common.delete", "title"), missing)
    }

    @Test
    fun `getMissingKeys ignores non-leaf nodes`() {
        val root = TranslationNode(
            key = "root", fullPath = "", values = emptyMap(),
            children = mutableMapOf(
                "section" to TranslationNode(
                    key = "section", fullPath = "section",
                    values = emptyMap(), // branch node, no values — should NOT be flagged
                    isLeaf = false,
                    children = mutableMapOf(
                        "item" to TranslationNode(
                            key = "item", fullPath = "section.item",
                            values = mapOf("en" to "Item", "fr" to "Element"),
                            isLeaf = true
                        )
                    )
                )
            )
        )

        val missing = viewModel.getMissingKeys(root, listOf("en", "fr"))
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `getMissingKeys with empty locales list returns empty set`() {
        val root = TranslationNode(
            key = "root", fullPath = "", values = emptyMap(),
            children = mutableMapOf(
                "key" to TranslationNode(
                    key = "key", fullPath = "key",
                    values = mapOf("en" to "Value"),
                    isLeaf = true
                )
            )
        )

        val missing = viewModel.getMissingKeys(root, emptyList())
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `getMissingKeys detects leaf with no values at all`() {
        val root = TranslationNode(
            key = "root", fullPath = "", values = emptyMap(),
            children = mutableMapOf(
                "orphan" to TranslationNode(
                    key = "orphan", fullPath = "orphan",
                    values = emptyMap(),
                    isLeaf = true
                )
            )
        )

        val missing = viewModel.getMissingKeys(root, listOf("en"))
        assertEquals(setOf("orphan"), missing)
    }

    // --- localeStates tests ---

    @Test
    fun `localeStates reports one state per locale, in the order given`() {
        val leaf = leaf("hello", mapOf("en" to "Hello", "fr" to "", "de" to "Hallo"))

        val states = viewModel.localeStates(leaf, listOf("en", "fr", "de", "es"))

        assertEquals(listOf("en", "fr", "de", "es"), states.keys.toList())
        assertEquals(LocaleState.PRESENT, states["en"])
        assertEquals(LocaleState.EMPTY, states["fr"])
        assertEquals(LocaleState.PRESENT, states["de"])
        assertEquals(LocaleState.MISSING, states["es"])
    }

    @Test
    fun `localeStates treats a whitespace-only value as empty`() {
        val states = viewModel.localeStates(leaf("hello", mapOf("en" to "   ")), listOf("en"))

        assertEquals(LocaleState.EMPTY, states["en"])
    }

    @Test
    fun `localeStates appends locales the key carries but the project did not discover`() {
        val states = viewModel.localeStates(leaf("hello", mapOf("en" to "Hello", "it" to "Ciao")), listOf("en"))

        assertEquals(listOf("en", "it"), states.keys.toList())
        assertEquals(LocaleState.PRESENT, states["it"])
    }

    @Test
    fun `localeStates returns nothing for a branch node`() {
        val branch = TranslationNode(key = "menu", fullPath = "menu", values = emptyMap())

        assertTrue(viewModel.localeStates(branch, listOf("en", "fr")).isEmpty())
    }

    // --- describeTree tests ---

    @Test
    fun `describeTree marks a leaf complete when every locale carries a value`() {
        val root = rootOf(leaf("hello", mapOf("en" to "Hello", "fr" to "Bonjour")))

        val status = viewModel.describeTree(root, listOf("en", "fr")).getValue("hello")

        assertEquals(KeyStatus.COMPLETE, status.status)
        assertEquals(NodeCompleteness(1, 1), status.completeness)
    }

    @Test
    fun `describeTree marks a leaf missing when a locale has no entry`() {
        val root = rootOf(leaf("hello", mapOf("en" to "Hello")))

        val status = viewModel.describeTree(root, listOf("en", "fr")).getValue("hello")

        assertEquals(KeyStatus.MISSING, status.status)
        assertEquals(NodeCompleteness(0, 1), status.completeness)
    }

    @Test
    fun `describeTree marks a leaf empty when a locale has a blank value`() {
        val root = rootOf(leaf("hello", mapOf("en" to "Hello", "fr" to "")))

        val status = viewModel.describeTree(root, listOf("en", "fr")).getValue("hello")

        assertEquals(KeyStatus.EMPTY, status.status)
        assertEquals(NodeCompleteness(0, 1), status.completeness)
    }

    @Test
    fun `describeTree gives a missing locale precedence over an empty one`() {
        val root = rootOf(leaf("hello", mapOf("en" to "")))

        val status = viewModel.describeTree(root, listOf("en", "fr")).getValue("hello")

        assertEquals(KeyStatus.MISSING, status.status)
    }

    @Test
    fun `describeTree counts completeness on a branch node`() {
        val root = rootOf(
            TranslationNode(
                key = "menu", fullPath = "menu", values = emptyMap(),
                children = mutableMapOf(
                    "home" to leaf("home", mapOf("en" to "Home", "fr" to "Accueil"), "menu.home"),
                    "about" to leaf("about", mapOf("en" to "About", "fr" to "À propos"), "menu.about"),
                    "help" to leaf("help", mapOf("en" to "Help"), "menu.help")
                )
            )
        )

        val statuses = viewModel.describeTree(root, listOf("en", "fr"))

        val menu = statuses.getValue("menu")
        assertEquals(NodeCompleteness(2, 3), menu.completeness)
        assertEquals(66, menu.completeness.percent)
        assertFalse(menu.completeness.isComplete)
        // A branch carries no per-locale badge: only its keys do.
        assertTrue(menu.localeStates.isEmpty())
    }

    @Test
    fun `describeTree propagates the worst descendant status up the branches`() {
        val root = rootOf(
            TranslationNode(
                key = "menu", fullPath = "menu", values = emptyMap(),
                children = mutableMapOf(
                    "ok" to leaf("ok", mapOf("en" to "Ok", "fr" to "Ok"), "menu.ok"),
                    "blank" to leaf("blank", mapOf("en" to "X", "fr" to " "), "menu.blank")
                )
            ),
            TranslationNode(
                key = "footer", fullPath = "footer", values = emptyMap(),
                children = mutableMapOf(
                    "gone" to leaf("gone", mapOf("en" to "Gone"), "footer.gone")
                )
            )
        )

        val statuses = viewModel.describeTree(root, listOf("en", "fr"))

        assertEquals(KeyStatus.EMPTY, statuses.getValue("menu").status)
        assertEquals(KeyStatus.MISSING, statuses.getValue("footer").status)
        // The root aggregates both: the worst one wins.
        assertEquals(KeyStatus.MISSING, statuses.getValue("").status)
        assertEquals(NodeCompleteness(1, 3), statuses.getValue("").completeness)
    }

    @Test
    fun `describeTree counts a node that is both a key and a branch`() {
        val menu = TranslationNode(
            key = "menu", fullPath = "menu",
            values = mapOf("en" to "Menu"),
            isLeaf = true,
            children = mutableMapOf(
                "home" to leaf("home", mapOf("en" to "Home", "fr" to "Accueil"), "menu.home")
            )
        )

        val statuses = viewModel.describeTree(rootOf(menu), listOf("en", "fr"))

        val status = statuses.getValue("menu")
        assertEquals(KeyStatus.MISSING, status.status)
        // Its own key counts alongside its children's.
        assertEquals(NodeCompleteness(1, 2), status.completeness)
        assertEquals(LocaleState.MISSING, status.localeStates["fr"])
    }

    @Test
    fun `describeTree reports an empty tree as complete`() {
        val statuses = viewModel.describeTree(
            TranslationNode(key = "root", fullPath = "", values = emptyMap()),
            listOf("en", "fr")
        )

        val root = statuses.getValue("")
        assertEquals(KeyStatus.COMPLETE, root.status)
        assertEquals(NodeCompleteness(0, 0), root.completeness)
        assertEquals(100, root.completeness.percent)
        assertTrue(root.completeness.isComplete)
    }

    @Test
    fun `describeTree describes every node of the tree`() {
        val root = rootOf(
            TranslationNode(
                key = "common", fullPath = "common", values = emptyMap(),
                children = mutableMapOf(
                    "save" to leaf("save", mapOf("en" to "Save"), "common.save")
                )
            )
        )

        val statuses = viewModel.describeTree(root, listOf("en"))

        assertEquals(setOf("", "common", "common.save"), statuses.keys)
    }

    // --- helpers ---

    private fun leaf(key: String, values: Map<String, String>, fullPath: String = key) =
        TranslationNode(key = key, fullPath = fullPath, values = values, isLeaf = true)

    private fun rootOf(vararg children: TranslationNode) = TranslationNode(
        key = "root", fullPath = "", values = emptyMap(),
        children = children.associateBy { it.key }.toMutableMap()
    )
}
