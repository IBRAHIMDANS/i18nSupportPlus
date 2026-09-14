package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.intellij.openapi.components.service
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A module's path template designates its translation files and states their locale and namespace.
 * The template used to be edited and previewed in the settings, then read by nothing.
 */
class ModuleSourcesTest : PlatformBaseTest() {

    private fun module(root: String, path: String, file: String = "") =
        ModuleConfig(name = root, rootDirectory = root, pathTemplate = path, fileTemplate = file)

    @Test
    fun templateStatesLocaleAndNamespace() {
        val modules = listOf(module("apps/web", "i18n/{lang}/{ns}.yml"))
        assertEquals(ModuleSources.Match("english", "home"), ModuleSources.match(modules, "apps/web/i18n/english/home.yml", anchored = true))
        assertNull(ModuleSources.match(modules, "apps/api/i18n/english/home.yml", anchored = true))
        assertEquals(ModuleSources.Match("fr", null), ModuleSources.match(listOf(module("apps/web", "messages/{locale}.json")), "apps/web/messages/fr.json", true))
    }

    @Test
    fun aTemplateWithoutExtensionOrLocaleOrWithAnUnknownPlaceholder() {
        assertEquals(ModuleSources.Match("de", "x"), ModuleSources.match(listOf(module("r", "{lang}/{ns}")), "r/de/x.json", true))
        assertNull(ModuleSources.match(listOf(module("r", "{ns}.json")), "r/common.json", true), "no locale placeholder: unusable")
        assertNull(ModuleSources.match(listOf(module("r", "{lang}/{oops}.json")), "r/en/a.json", true), "unknown placeholder: unusable")
    }

    /** A file whose names guess no locale is still found, with the locale the template states. */
    @Test
    fun theScanFindsTemplateFilesTheGuessMisses() = myFixture.runWithConfig(Config(modules = listOf(module("apps/web", "messages/{lang}.json")))) {
        addFileToProject("apps/web/messages/english.json", """{"title": "Hi"}""")

        val sources = project.service<LocalizationSourceService>().findAllSources(project)
            .filter { it.displayPath.endsWith("messages/english.json") }

        assertEquals(1, sources.size, "a template file must be scanned even though `english` is no ISO code")
        assertEquals("english", sources.single().localeLabel())
        assertEquals(Config().defaultNs, TranslationDataLoader.extractNamespace(sources.single()))
    }
}
