package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** Which files of a namespace the extraction dialog offers. */
class ExtractKeyModelSourcesTest : PlatformBaseTest() {

    private fun allSources(): List<LocalizationSource> =
        ReadAction.compute<List<LocalizationSource>, RuntimeException> {
            // The lookup by name the dialog runs per namespace: the one that caught `poc/…/common.json`.
            project.service<LocalizationSourceService>().findSources(listOf("common", "test"), project)
        }

    /**
     * What the dialog offers to a code file at [callerPath], among the sources under [folders]:
     * the light fixture's project outlives a test, and so do the files of the others. Paths are
     * read relative to the fixture root, which `displayPath` prefixes.
     */
    private fun offered(callerPath: String, vararg folders: String): List<String> {
        val sources = allSources().filter { source -> folders.any { source.displayPath.contains("$it/") } }
        val anchor = sources.map { it.displayPath.trim('/') }.first { it.contains("${folders.first()}/") }
        val root = anchor.substring(0, anchor.indexOf("${folders.first()}/"))
        return ExtractKeyModel.offered(sources, root + callerPath).map { it.displayPath.trim('/').removePrefix(root) }.sorted()
    }

    /**
     * A `common.json` with no locale in another folder, and another app's locales: only the
     * locales of the root nearest the code file are offered.
     */
    @Test
    fun theLocalesOfTheNearestRootOnly() {
        addFileToProject("cbox-front/public/locales/en/common.json", "{}")
        addFileToProject("cbox-front/public/locales/fr/common.json", "{}")
        addFileToProject("poc/thinkfree/serverless/common.json", "{}")
        addFileToProject("admin/locales/en/common.json", "{}")
        val folders = arrayOf("cbox-front", "poc", "admin")

        Assertions.assertEquals(
            listOf("cbox-front/public/locales/en/common.json", "cbox-front/public/locales/fr/common.json"),
            offered("cbox-front/src/features/account/AccountModals.tsx", *folders)
        )
        Assertions.assertEquals(listOf("admin/locales/en/common.json"), offered("admin/src/App.tsx", "admin", "cbox-front", "poc"))
    }

    /** A project whose files name no locale keeps them: it has one language, unnamed. */
    @Test
    fun filesWithoutLocaleAreKeptWhenNoneHasOne() {
        addFileToProject("unnamed/assets/test.json", "{}")
        Assertions.assertEquals(listOf("unnamed/assets/test.json"), offered("unnamed/src/App.tsx", "unnamed"))
    }
}
