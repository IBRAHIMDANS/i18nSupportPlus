package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A module's path template designates its translation files and states their locale and namespace;
 * its root directory says which files it owns. All of it is read from paths.
 */
class ModuleSourcesTest {

    private fun module(root: String, path: String = "", file: String = "") =
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

    @Test
    fun anUnanchoredPathOnlyNeedsToEndWithTheTemplate() {
        val modules = listOf(module("apps/web", "messages/{lang}.json"))
        assertEquals(ModuleSources.Match("fr", null), ModuleSources.match(modules, ModuleSources.FilePath("/tmp/x/apps/web/messages/fr.json", anchored = false)))
        assertNull(ModuleSources.match(modules, ModuleSources.FilePath("tmp/x/apps/web/messages/fr.json", anchored = true)))
    }

    @Test
    fun aPathUnderTheBaseIsAnchoredAndRelative() {
        assertEquals(ModuleSources.FilePath("apps/web/en.json", true), ModuleSources.FilePath.of("/p/apps/web/en.json", "/p"))
        assertEquals(ModuleSources.FilePath("/q/en.json", false), ModuleSources.FilePath.of("/q/en.json", "/p"))
        assertEquals(ModuleSources.FilePath("/p2/en.json", false), ModuleSources.FilePath.of("/p2/en.json", "/p"), "a sibling sharing the prefix")
        assertEquals(ModuleSources.FilePath("/p/en.json", false), ModuleSources.FilePath.of("/p/en.json", ""), "no base path")
    }

    @Test
    fun theOwnerIsTheInnermostModuleHoldingTheFile() {
        val web = module("apps/web")
        val admin = module("apps/web/admin")
        val modules = listOf(web, admin, module(""))

        assertEquals(admin, ModuleSources.owner(modules, ModuleSources.FilePath("apps/web/admin/en.json", true)))
        assertEquals(web, ModuleSources.owner(modules, ModuleSources.FilePath("apps/web/en.json", true)))
        assertEquals(web, ModuleSources.owner(modules, ModuleSources.FilePath("/tmp/apps/web/en.json", false)))
        assertNull(ModuleSources.owner(modules, ModuleSources.FilePath("apps/web-admin/en.json", true)), "whole segments only")
    }

    @Test
    fun aModuleWithoutRootHoldsNothing() {
        assertFalse(ModuleSources.contains(module(""), ModuleSources.FilePath("en.json", true)))
        assertTrue(ModuleSources.contains(module("/apps/web/"), ModuleSources.FilePath("/apps/web/en.json", true)))
    }

    @Test
    fun theLoneModuleIsTheOnlyOneWithARoot() {
        val web = module("apps/web")
        assertEquals(web, ModuleSources.lone(listOf(web, module(""))))
        assertNull(ModuleSources.lone(listOf(web, module("apps/admin"))))
        assertNull(ModuleSources.lone(emptyList()))
    }

    @Test
    fun hasTemplatesOnlyForAUsableOne() {
        assertFalse(ModuleSources.hasTemplates(listOf(module("r"), module("r", "{ns}.json"))))
        assertTrue(ModuleSources.hasTemplates(listOf(module("r", "{lang}.json"))))
    }
}
