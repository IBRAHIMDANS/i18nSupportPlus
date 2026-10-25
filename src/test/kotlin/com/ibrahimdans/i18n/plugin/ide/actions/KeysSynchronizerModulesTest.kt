package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * *Sync Keys* in a monorepo: the locales of a module are compared with each other, never with
 * another module's. Comparing the whole project proposed admin's keys to web's files.
 */
class KeysSynchronizerModulesTest : PlatformBaseTest() {

    private fun addMonorepo() {
        myFixture.addFileToProject("apps/web/locales/en/common.json", """{"title": "Web", "save": "Save"}""")
        myFixture.addFileToProject("apps/web/locales/fr/common.json", """{"title": "Web"}""")
        myFixture.addFileToProject("apps/admin/locales/en/common.json", """{"menu": "Menu"}""")
        myFixture.addFileToProject("apps/admin/locales/fr/common.json", """{"menu": "Menu"}""")
    }

    private fun missing(config: Config): List<Pair<String, String>> {
        var entries = emptyList<Pair<String, String>>()
        myFixture.runWithConfig(config) {
            addMonorepo()
            read {
                entries = KeysSynchronizer().missingEntries(project, config).map { entry ->
                    entry.key to entry.source.displayPath.substringAfter("apps/", "?")
                }
            }
        }
        return entries
    }

    @Test
    fun aKeyIsMissingOnlyFromTheLocalesOfItsOwnModule() {
        val modules = Config(modules = listOf(
            ModuleConfig(name = "web", rootDirectory = "apps/web"),
            ModuleConfig(name = "admin", rootDirectory = "apps/admin"),
        ))

        assertEquals(listOf("common:save" to "web/locales/fr/common.json"), missing(modules))
    }

    @Test
    fun withoutModulesTheProjectIsOneScope() {
        val keys = missing(Config()).map { it.first }.toSet()

        assertEquals(setOf("common:save", "common:title", "common:menu"), keys, "every key of one locale is proposed to the others")
    }
}
