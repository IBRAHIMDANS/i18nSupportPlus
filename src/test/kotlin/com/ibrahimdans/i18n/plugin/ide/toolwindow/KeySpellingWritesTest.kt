package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The tool window reads keys with the configured separator and writes them back the same way.
 * Both halves used to hard-code `.`: an edit on a flat key created a nested object next to it,
 * and a project nesting with another separator was split on the wrong character.
 */
class KeySpellingWritesTest : PlatformBaseTest() {

    private val viewModel = TableViewModel()

    private fun text(path: String): String = ReadAction.compute<String, RuntimeException> {
        PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!.text
    }

    @Test
    fun editingAFlatKeyUpdatesItInPlace() = myFixture.runWithConfig(Config(flatKeys = true)) {
        addFileToProject("locales/en.json", """{"app.title": "Old"}""")

        assertEquals(listOf("app.title"), viewModel.loadRows(project).map { it.key })
        assertTrue(viewModel.saveValue(project, "app.title", "en", "New"))

        val written = text("locales/en.json")
        assertTrue(written.contains("\"app.title\": \"New\""), written)
        assertFalse(written.contains("\"app\":"), "a flat key must not be split into levels: $written")
    }

    @Test
    fun customKeySeparatorIsReadAndWrittenBack() = myFixture.runWithConfig(Config(keySeparator = "/")) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home"}}""")

        assertEquals(listOf("common:menu/home"), viewModel.loadRows(project).map { it.key })
        assertTrue(viewModel.saveValue(project, "common:menu/home", "en", "Start"))

        val written = text("locales/en/common.json")
        assertTrue(written.contains("\"home\": \"Start\""), written)
    }

    @Test
    fun flatKeyStaysOneSegment() {
        val flat = KeysSynchronizer().buildFullKey("common:app.title", Config(flatKeys = true))
        assertEquals("common", flat.ns?.text)
        assertEquals(listOf("app.title"), flat.compositeKey.map { it.text })

        val nested = KeysSynchronizer().buildFullKey("common:menu/home", Config(keySeparator = "/"))
        assertEquals(listOf("menu", "home"), nested.compositeKey.map { it.text })
    }

    @Test
    fun treeLevelsFollowTheKeySeparator() {
        val root = TreeViewModel().buildTree(mapOf("common:menu/home" to mapOf("en" to "Home")), Config(keySeparator = "/"))
        val menu = root.children.getValue("common:menu")
        val home = menu.children.getValue("home")
        assertEquals("common:menu/home", home.fullPath)
        assertTrue(home.isLeaf)

        val flatRoot = TreeViewModel().buildTree(mapOf("app.title" to mapOf("en" to "T")), Config(flatKeys = true))
        assertEquals(setOf("app.title"), flatRoot.children.keys)
    }
}
