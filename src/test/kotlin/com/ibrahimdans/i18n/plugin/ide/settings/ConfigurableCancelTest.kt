package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import com.ibrahimdans.i18n.plugin.ide.toolwindow.ToolWindowViewState
import com.ibrahimdans.i18n.plugin.ide.whatsnew.WhatsNewPreferences
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The settings pages write [Settings] as they are typed. Closing the dialog without validating —
 * *Cancel* — puts back what was last validated; *OK* validates before closing, and keeps it.
 */
internal class ConfigurableCancelTest : PlatformBaseTest() {

    private val settings get() = Settings.getInstance(project)

    @Test
    fun `cancel puts back a setting typed on the main page`() = myFixture.runWithConfig(Config(defaultNs = "common")) {
        val page = Configurable(project)
        page.createComponent()
        settings.defaultNs = "typed"

        page.disposeUIResources()

        Assertions.assertEquals("common", settings.defaultNs)
    }

    @Test
    fun `ok keeps a setting typed on the main page`() = myFixture.runWithConfig(Config(defaultNs = "common")) {
        val page = Configurable(project)
        page.createComponent()
        settings.defaultNs = "typed"

        page.apply()
        page.disposeUIResources()

        Assertions.assertEquals("typed", settings.defaultNs)
    }

    @Test
    fun `cancel after apply puts back the applied value`() = myFixture.runWithConfig(Config(defaultNs = "common")) {
        val page = Configurable(project)
        page.createComponent()
        settings.defaultNs = "applied"
        page.apply()
        settings.defaultNs = "typed after"

        page.disposeUIResources()

        Assertions.assertEquals("applied", settings.defaultNs)
    }

    @Test
    fun `cancel puts back announce plugin updates`() {
        val before = WhatsNewPreferences.announceUpdates
        try {
            val page = Configurable(project)
            page.createComponent()
            WhatsNewPreferences.announceUpdates = !before
            Assertions.assertTrue(page.isModified, "an application setting changed counts as a change")

            page.disposeUIResources()

            Assertions.assertEquals(before, WhatsNewPreferences.announceUpdates)
        } finally {
            WhatsNewPreferences.announceUpdates = before
        }
    }

    @Test
    fun `cancel on the main page leaves the modules validated on their page`() = myFixture.runWithConfig(Config()) {
        val main = Configurable(project)
        main.createComponent()
        val modules = ModulesConfigurable(project)
        modules.createComponent()
        settings.modules = mutableListOf(ModuleConfig(name = "shop", rootDirectory = "apps/shop"))
        modules.apply()

        main.disposeUIResources()
        modules.disposeUIResources()

        Assertions.assertEquals(listOf("shop"), settings.modules.map { it.name })
    }

    @Test
    fun `cancel puts back the modules and the rules`() = myFixture.runWithConfig(Config()) {
        val modules = ModulesConfigurable(project)
        modules.createComponent()
        val rules = RulesConfigurable(project)
        rules.createComponent()
        settings.modules = mutableListOf(ModuleConfig(name = "shop"))
        settings.rules = mutableListOf(EditorRuleState(id = "translate"))

        modules.disposeUIResources()
        rules.disposeUIResources()

        Assertions.assertTrue(settings.modules.isEmpty(), "modules typed then cancelled")
        Assertions.assertTrue(settings.rules.isEmpty(), "rules typed then cancelled")
    }

    @Test
    fun `a module renamed with ok keeps its view state`() {
        val web = ModuleConfig(name = "cancel-test-web", rootDirectory = "apps/web")
        val shop = web.copy(name = "cancel-test-shop")
        val viewState = ToolWindowViewState.getInstance(project)
        myFixture.runWithConfig(Config(modules = listOf(web))) {
            viewState.setHiddenLocales(web, setOf("de"))
            try {
                val page = ModulesConfigurable(project)
                page.createComponent()
                settings.modules = mutableListOf(shop)

                page.apply()
                page.disposeUIResources()

                Assertions.assertEquals(setOf("de"), viewState.hiddenLocales(shop))
                Assertions.assertTrue(viewState.hiddenLocales(web).isEmpty(), "the old name's entry moved")
            } finally {
                viewState.setHiddenLocales(web, emptySet())
                viewState.setHiddenLocales(shop, emptySet())
            }
        }
    }

    @Test
    fun `a module renamed then cancelled keeps its name and its view state`() {
        val web = ModuleConfig(name = "cancel-test-web", rootDirectory = "apps/web")
        val shop = web.copy(name = "cancel-test-shop")
        val viewState = ToolWindowViewState.getInstance(project)
        myFixture.runWithConfig(Config(modules = listOf(web))) {
            viewState.setHiddenLocales(web, setOf("de"))
            try {
                val page = ModulesConfigurable(project)
                page.createComponent()
                settings.modules = mutableListOf(shop)

                page.disposeUIResources()

                Assertions.assertEquals(listOf("cancel-test-web"), settings.modules.map { it.name })
                Assertions.assertEquals(setOf("de"), viewState.hiddenLocales(web))
                Assertions.assertTrue(viewState.hiddenLocales(shop).isEmpty(), "nothing moved to the cancelled name")
            } finally {
                viewState.setHiddenLocales(web, emptySet())
                viewState.setHiddenLocales(shop, emptySet())
            }
        }
    }
}
