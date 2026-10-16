package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Inclusion rules of the "all sources" scan: [LocalizationSourceService.isIncluded] and
 * [LocalizationSourceService.isUnderRoot].
 *
 * Checked directly with the fixture's own content root as base path: the light fixture keeps
 * its files in an in-memory VFS rooted at `/src`, while `project.basePath` is a real temp
 * directory, so going through the project could never put a file under the configured root.
 */
class LocalizationSourceServiceRootTest : PlatformBaseTest() {

    private fun file(relativePath: String): VirtualFile =
        addFileToProject(relativePath, CONTENT).virtualFile

    /** The fixture's content root: the directory [relativePath] starts from. */
    private fun basePathOf(file: VirtualFile, relativePath: String): String {
        val basePath = file.path.removeSuffix("/$relativePath")
        Assertions.assertNotEquals(file.path, basePath, "the fixture must keep the relative path")
        return basePath
    }

    @Test
    fun aFileUnderTheRootIsIncluded() {
        val inRoot = file("locales/en/common.json")
        val basePath = basePathOf(inRoot, "locales/en/common.json")

        Assertions.assertTrue(LocalizationSourceService.isIncluded(inRoot, "locales", basePath))
        Assertions.assertTrue(LocalizationSourceService.isUnderRoot(inRoot, "locales", basePath))
    }

    @Test
    fun aFileUnderANestedRootIsIncluded() {
        val inRoot = file("public/locales/messages.json")
        val basePath = basePathOf(inRoot, "public/locales/messages.json")

        Assertions.assertTrue(
            LocalizationSourceService.isIncluded(inRoot, "public/locales", basePath),
            "a root with several segments is matched as a whole path"
        )
    }

    @Test
    fun aSiblingFolderSharingTheRootPrefixIsExcluded() {
        val sibling = file("locales2/en.json")
        val basePath = basePathOf(sibling, "locales2/en.json")

        Assertions.assertFalse(
            LocalizationSourceService.isIncluded(sibling, "locales", basePath),
            "`locales2/` is not under `locales`, even though the file name looks like a locale"
        )
    }

    @Test
    fun aFileOutsideTheRootIsExcluded() {
        val outOfRoot = file("src/fr.json")
        val basePath = basePathOf(outOfRoot, "src/fr.json")

        Assertions.assertFalse(
            LocalizationSourceService.isIncluded(outOfRoot, "locales", basePath),
            "a configured root replaces the locale heuristic"
        )
    }

    @Test
    fun theRootItselfIsUnderTheRoot() {
        val inRoot = file("locales/en.json")
        val basePath = basePathOf(inRoot, "locales/en.json")

        Assertions.assertTrue(LocalizationSourceService.isUnderRoot(inRoot.parent, "locales", basePath))
    }

    @Test
    fun aRootWrittenWithATrailingSlashMatchesTheSameFiles() {
        val inRoot = file("locales/en.json")
        val sibling = file("locales2/en.json")
        val basePath = basePathOf(inRoot, "locales/en.json")

        Assertions.assertTrue(LocalizationSourceService.isIncluded(inRoot, "locales/", basePath))
        Assertions.assertTrue(LocalizationSourceService.isUnderRoot(inRoot.parent, "locales/", basePath))
        Assertions.assertFalse(LocalizationSourceService.isIncluded(sibling, "locales/", basePath))
    }

    @Test
    fun withoutRootALocaleFolderIsIncluded() {
        val inLocaleDir = file("i18n/pt-BR/common.json")
        val basePath = basePathOf(inLocaleDir, "i18n/pt-BR/common.json")

        Assertions.assertTrue(
            LocalizationSourceService.isIncluded(inLocaleDir, "", basePath),
            "the parent folder looks like a locale"
        )
    }

    @Test
    fun withoutRootALocaleFileNameIsIncluded() {
        val localeNamed = file("i18n/fr.json")
        val basePath = basePathOf(localeNamed, "i18n/fr.json")

        Assertions.assertTrue(
            LocalizationSourceService.isIncluded(localeNamed, "  ", basePath),
            "a blank root falls back to the heuristic, and the file stem looks like a locale"
        )
    }

    @Test
    fun withoutRootAnUnrelatedFileIsExcluded() {
        val unrelated = file("config/settings.json")
        val basePath = basePathOf(unrelated, "config/settings.json")

        Assertions.assertFalse(
            LocalizationSourceService.isIncluded(unrelated, "", basePath),
            "neither the folder nor the stem looks like a locale"
        )
    }

    private companion object {
        const val CONTENT = "{}"
    }
}
