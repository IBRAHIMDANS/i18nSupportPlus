package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** The files the editor actions are offered in follow the file types the registered `Lang`s read. */
class I18nEditorScopeTest : PlatformBaseTest() {

    private fun isCode(path: String, text: String = ""): Boolean =
        I18nEditorScope.isCode(myFixture.addFileToProject(path, text))

    @Test
    fun theFilesALangReadsAreCode() {
        listOf(
            "src/app.js", "src/App.tsx", "src/App.vue", "src/index.php",
            "resources/views/home.blade.php", "templates/home.html.twig", "src/index.html",
        ).forEach { path -> Assertions.assertTrue(isCode(path), path) }
    }

    @Test
    fun otherFilesAreNotCode() {
        // A Markdown file holds HTML in its view: by the file type alone, it is not code.
        listOf("src/Main.java", "README.md", "notes.txt").forEach { path -> Assertions.assertFalse(isCode(path), path) }
    }

    @Test
    fun aTranslationFileIsCodeOrTranslation() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        val file = myFixture.addFileToProject("locales/en/translation.json", """{"title": "Title"}""")
        Assertions.assertFalse(I18nEditorScope.isCode(file))
        Assertions.assertTrue(I18nEditorScope.isCodeOrTranslation(file))
    }
}
