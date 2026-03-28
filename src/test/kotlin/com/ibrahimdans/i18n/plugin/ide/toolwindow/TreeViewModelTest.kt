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
}
