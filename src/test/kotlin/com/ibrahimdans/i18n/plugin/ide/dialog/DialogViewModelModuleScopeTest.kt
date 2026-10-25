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
}
