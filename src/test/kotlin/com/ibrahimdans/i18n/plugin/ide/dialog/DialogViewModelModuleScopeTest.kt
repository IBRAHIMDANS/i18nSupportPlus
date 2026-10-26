package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * *Edit Translations* opened from a code file shows the sources of that file's module (#620): it used
 * to list every source of the project, another application's files and locales included.
 */
class DialogViewModelModuleScopeTest : PlatformBaseTest() {

    private val monorepo = Config(modules = listOf(
        ModuleConfig(name = "app", rootDirectory = "apps/app"),
        ModuleConfig(name = "webapp", rootDirectory = "apps/webapp"),
    ))

    private val title = FullKey(source = "common:title", ns = Literal("common"), compositeKey = listOf(Literal("title")))

    @Test
    fun theDialogOpenedFromCodeListsTheSourcesOfItsModule() = myFixture.runWithConfig(monorepo) {
        addFileToProject("apps/app/locales/en/common.json", """{"title": "App"}""")
        addFileToProject("apps/webapp/locales/en/common.json", """{"title": "Web"}""")
        addFileToProject("apps/webapp/locales/sv/common.json", """{"title": "Webb"}""")
        val caller = addFileToProject("apps/app/src/Home.tsx", "t('common:title')")

        val scoped = DialogViewModel(project).loadTranslations(title, caller)
        assertEquals(listOf("App"), scoped.values.toList(), "only app's file: ${scoped.keys.map { it.displayPath }}")

        val projectWide = DialogViewModel(project).loadTranslations(title)
        assertEquals(setOf("App", "Web", "Webb"), projectWide.values.toSet(), "without a caller, the whole project")
    }

    /** Opened from a tool window tab (#687): no code file, the tab's module scopes the sources the same way. */
    @Test
    fun theDialogOpenedFromATabListsTheSourcesOfItsModule() = myFixture.runWithConfig(monorepo) {
        addFileToProject("apps/app/locales/en/common.json", """{"title": "App"}""")
        addFileToProject("apps/webapp/locales/en/common.json", """{"title": "Web"}""")
        val viewModel = DialogViewModel(project)
        val app = monorepo.modules.first()

        assertEquals(listOf("App"), viewModel.loadTranslations(title, module = app).values.toList())
        assertEquals(1, viewModel.loadSourcesForNamespace("common", module = app).size, "creation writes app's file only")
    }

    /**
     * A key only `webapp` defines, created from `app`'s code (#687): saving writes `app`'s files and
     * overwrites nothing, so it is not taken; the dialog names the module that holds it instead.
     */
    @Test
    fun aKeyOnlyAnotherModuleDefinesIsNotTakenAndNamesThatModule() = myFixture.runWithConfig(monorepo) {
        addFileToProject("apps/app/locales/en/common.json", """{"title": "App"}""")
        addFileToProject("apps/webapp/locales/en/common.json", """{"login": "Log in"}""")
        val caller = addFileToProject("apps/app/src/Home.tsx", "t('common:login')")
        val viewModel = DialogViewModel(project)
        val shown = viewModel.loadSourcesForNamespace("common", caller).keys

        assertEquals(setOf("title"), viewModel.existingKeys("common", shown))
        assertEquals(mapOf("login" to listOf("webapp")), viewModel.keysInOtherModules("common", shown))
    }

    @Test
    fun withoutModulesNoOtherModuleIsNamed() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", """{"title": "App"}""")
        val caller = addFileToProject("src/Home.tsx", "t('common:title')")
        val viewModel = DialogViewModel(project)
        val shown = viewModel.loadSourcesForNamespace("common", caller).keys

        assertEquals(setOf("title"), viewModel.existingKeys("common", shown))
        assertEquals(emptyMap<String, List<String>>(), viewModel.keysInOtherModules("common", shown))
    }
}
