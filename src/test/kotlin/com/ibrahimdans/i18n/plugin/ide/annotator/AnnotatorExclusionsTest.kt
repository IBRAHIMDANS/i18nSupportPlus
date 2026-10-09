package com.ibrahimdans.i18n.plugin.ide.annotator

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.lang.annotation.HighlightSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The excluded directories and file extensions of the settings silence the annotator, and only there. */
class AnnotatorExclusionsTest : PlatformBaseTest() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("locales/en/test.json", """{"ref": {"title": "x"}}""")
    }

    private fun errorsOf(path: String): List<String?> {
        val file = myFixture.addFileToProject(path, "export const a = (t) => t('test:ref.missing');")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.description }
    }

    @Test
    fun aFileInAnExcludedDirectoryIsNotAnnotated() = myFixture.runWithConfig(Config(excludedDirectories = "legacy, vendor")) {
        assertEquals(emptyList<String>(), errorsOf("legacy/App.js"))
        assertEquals(listOf("Unresolved key"), errorsOf("src/App.js"))
    }

    @Test
    fun aFileOfAnExcludedExtensionIsNotAnnotated() = myFixture.runWithConfig(Config(excludedFileExtensions = ".JSX")) {
        assertEquals(emptyList<String>(), errorsOf("src/Button.jsx"))
        assertEquals(listOf("Unresolved key"), errorsOf("src/Button.js"))
    }
}
