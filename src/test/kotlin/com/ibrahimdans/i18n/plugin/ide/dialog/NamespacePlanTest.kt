package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel.NamespacePlan
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Where *Add Namespace* writes. The locale used to be the file's parent directory and the files
 * always `.json`: a YAML project got JSON, and `locales/en.json` got a namespace file named after
 * no locale at all. The plan is pure, so it is tested without the VFS the light fixture cannot
 * align with the project's base path.
 */
class NamespacePlanTest {

    private fun source(displayPath: String): LocalizationSource = mockk(relaxed = true) {
        every { name } returns displayPath.substringAfterLast('/')
        every { parent } returns displayPath.substringBeforeLast('/').substringAfterLast('/')
        every { this@mockk.displayPath } returns displayPath
        // A relaxed mock answers "" for these, which reads as a locale a template stated.
        every { locale } returns null
        every { namespace } returns null
    }

    private fun plan(vararg paths: String, config: Config = Config()) =
        DialogViewModel.planNamespace("auth", paths.map(::source), config, "/project")

    @Test
    fun oneFilePerLocaleDirectoryNextToTheExistingNamespaces() {
        assertEquals(
            NamespacePlan.Create(listOf("/project/locales/en/auth.json", "/project/locales/fr/auth.json")),
            plan("locales/en/common.json", "locales/en/home.json", "locales/fr/common.json")
        )
    }

    @Test
    fun theExtensionOfTheExistingFilesIsKept() {
        assertEquals(
            NamespacePlan.Create(listOf("/project/locales/en/auth.yml")),
            plan("locales/en/common.yml")
        )
    }

    @Test
    fun aOneFilePerLocaleLayoutIsRefused() {
        assertEquals(NamespacePlan.OneFilePerLocale, plan("locales/en.json", "locales/fr.json"))
    }

    @Test
    fun anEmptyProjectFallsBackToTheConfiguredRootAndFormat() {
        assertEquals(
            NamespacePlan.Create(listOf("/project/i18n/en/auth.yml", "/project/i18n/fr/auth.yml")),
            plan(config = Config(translationsRoot = "i18n", preferredLocalization = "yaml"))
        )
    }

    @Test
    fun emptyContentFollowsTheFormat() {
        assertEquals("{}\n", DialogViewModel.emptyContentFor("auth.json"))
        assertEquals("", DialogViewModel.emptyContentFor("auth.yml"))
    }
}
