package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** *Focus on a Module* and *Activate All Modules*: one settings change each, and its *Undo*. */
class ModuleFocusTest : PlatformBaseTest() {

    private val web = ModuleConfig(name = "web", rootDirectory = "apps/web")
    private val admin = ModuleConfig(name = "admin", rootDirectory = "apps/admin")
    private val blog = ModuleConfig(name = "blog", rootDirectory = "apps/blog", enabled = false)

    private fun enabled(): Map<String, Boolean> = Settings.getInstance(project).config().modules.associate { it.name to it.enabled }

    @Test
    fun focusKeepsOnlyTheChosenModuleActive() = myFixture.runWithConfig(Config(modules = listOf(web, admin, blog))) {
        ModuleFocus.focus(project, admin)

        assertEquals(mapOf("web" to false, "admin" to true, "blog" to false), enabled())
    }

    @Test
    fun activateAllBringsEveryModuleBack() = myFixture.runWithConfig(Config(modules = listOf(web, admin, blog))) {
        ModuleFocus.activateAll(project)

        assertEquals(mapOf("web" to true, "admin" to true, "blog" to true), enabled())
    }

    @Test
    fun undoGivesEveryModuleItsStateBack() = myFixture.runWithConfig(Config(modules = listOf(web, admin, blog))) {
        val before = Settings.getInstance(project).config().modules.map { it.enabled }
        ModuleFocus.focus(project, admin)

        ModuleFocus.undo(project, before)

        assertEquals(mapOf("web" to true, "admin" to true, "blog" to false), enabled())
    }

    @Test
    fun theActionsNeedTwoModules() {
        myFixture.runWithConfig(Config()) { assertFalse(ModuleFocus.isAvailable(project)) }
        myFixture.runWithConfig(Config(modules = listOf(web))) { assertFalse(ModuleFocus.isAvailable(project)) }
        myFixture.runWithConfig(Config(modules = listOf(web, admin))) { assertTrue(ModuleFocus.isAvailable(project)) }
    }
}
