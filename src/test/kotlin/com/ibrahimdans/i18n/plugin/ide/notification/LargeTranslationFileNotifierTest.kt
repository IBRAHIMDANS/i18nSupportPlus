package com.ibrahimdans.i18n.plugin.ide.notification

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.vfs.limits.FileSizeLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LargeTranslationFileNotifierTest : PlatformBaseTest() {

    /** A JSON translation file a little above the IDE's intellisense limit. */
    private fun oversizedJson(): String {
        val limit = FileSizeLimit.getIntellisenseLimit("json")
        val entry = "\"key%07d\": \"value\",\n"
        val count = limit / entry.format(0).length + 1_000
        return (0 until count).joinToString("", "{\n", "\"last\": \"value\"\n}") { entry.format(it) }
    }

    @Test
    fun translationFileAboveTheLimitIsReported() {
        val file = addFileToProject("locales/en.json", oversizedJson()).virtualFile
        addFileToProject("locales/fr.json", """{"hello": "Bonjour"}""")

        assertEquals(listOf(file), LargeTranslationFileNotifier.oversizedFiles(project))
    }

    @Test
    fun translationFilesBelowTheLimitAreNotReported() {
        addFileToProject("locales/en.json", """{"hello": "Hello"}""")
        addFileToProject("locales/fr.json", """{"hello": "Bonjour"}""")

        assertTrue(LargeTranslationFileNotifier.oversizedFiles(project).isEmpty())
    }

    @Test
    fun largeJsonOutsideTheTranslationsIsNotReported() {
        // A package-lock.json is as large, but no translation source: nothing to tell.
        addFileToProject("package-lock.json", oversizedJson())
        addFileToProject("locales/en.json", """{"hello": "Hello"}""")

        assertTrue(LargeTranslationFileNotifier.oversizedFiles(project).isEmpty())
    }

    // ---- which oversized files are sources: every file taken as too large, to keep the fixtures small ----

    private fun everyFileTooLarge() = LargeTranslationFileNotifier.oversizedFiles(project) { true }

    @Test
    fun onlyTheTranslationFilesAreTold() {
        val en = addFileToProject("locales/en.json", """{"hello": "Hello"}""").virtualFile
        addFileToProject("tsconfig.json", """{"compilerOptions": {}}""")
        addFileToProject("src/App.js", "export default 1")

        assertEquals(listOf(en), everyFileTooLarge())
    }

    @Test
    fun excludedDirectoryIsNotTold() {
        addFileToProject("node_modules/some-lib/locales/en.json", """{"hello": "Hello"}""")

        assertTrue(everyFileTooLarge().isEmpty(), "node_modules is never searched for translations")
    }
}
