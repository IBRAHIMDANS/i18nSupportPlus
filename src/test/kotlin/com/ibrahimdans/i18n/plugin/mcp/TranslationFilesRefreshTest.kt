package com.ibrahimdans.i18n.plugin.mcp

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Before a tool answers, the folders of the translation files are refreshed with their content, and the folder
 * above them alone: enough to see a value or a locale an agent wrote outside the IDE, without walking the project.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class TranslationFilesRefreshTest : PlatformBaseTest() {

    private fun paths(files: List<VirtualFile>) =
        files.map { it.path.substringAfter("/src/") }.sorted()

    @Test
    fun `one folder per locale is refreshed with its content, the folder above them alone`() {
        val en = myFixture.addFileToProject("locales/en/common.json", "{}").virtualFile
        val enAuth = myFixture.addFileToProject("locales/en/auth.json", "{}").virtualFile
        val fr = myFixture.addFileToProject("locales/fr/common.json", "{}").virtualFile

        val (folders, parents) = TranslationFilesRefresh.foldersOf(listOf(en, enAuth, fr))

        Assertions.assertEquals(listOf("locales/en", "locales/fr"), paths(folders))
        Assertions.assertEquals(listOf("locales"), paths(parents))
    }

    @Test
    fun `a folder that holds files is never refreshed alone as well`() {
        val en = myFixture.addFileToProject("locales/en.json", "{}").virtualFile
        val de = myFixture.addFileToProject("locales/de/common.json", "{}").virtualFile

        val (folders, parents) = TranslationFilesRefresh.foldersOf(listOf(en, de))

        Assertions.assertEquals(listOf("locales", "locales/de"), paths(folders))
        Assertions.assertFalse(parents.any { it.path.endsWith("/locales") }, "locales is already refreshed with its content")
    }

    @Test
    fun `no translation file, nothing to refresh`() {
        val (folders, parents) = TranslationFilesRefresh.foldersOf(emptyList())

        Assertions.assertTrue(folders.isEmpty() && parents.isEmpty())
    }
}
