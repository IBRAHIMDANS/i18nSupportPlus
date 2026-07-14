package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Exercises [TableViewModel.saveValue], the write path behind the Table View's
 * in-place cell editing: update of an existing value, creation of a missing
 * entry in a locale, and refusal (false) when no translation file matches.
 */
class TableViewModelSaveValueTest : PlatformBaseTest() {

    private val viewModel = TableViewModel()

    private fun valueAt(path: String, vararg key: String): String? =
        ReadAction.compute<String?, RuntimeException> {
            val vf = myFixture.findFileInTempDir(path) ?: return@compute null
            val file = PsiManager.getInstance(project).findFile(vf) as? JsonFile ?: return@compute null
            var node = file.topLevelValue as? JsonObject ?: return@compute null
            for (i in 0 until key.size - 1) node = node.findProperty(key[i])?.value as? JsonObject ?: return@compute null
            (node.findProperty(key.last())?.value as? JsonStringLiteral)?.value
        }

    @Test
    fun saveValue_updatesExistingValue() {
        addFileToProject("locales/en/common.json", """{"menu":{"home":"Home"}}""")

        val ok = viewModel.saveValue(project, "common:menu.home", "en", "Homepage")

        Assertions.assertTrue(ok)
        Assertions.assertEquals("Homepage", valueAt("locales/en/common.json", "menu", "home"))
    }

    @Test
    fun saveValue_createsMissingEntryInLocale() {
        addFileToProject("locales/en/common.json", """{"menu":{"home":"Home"}}""")
        addFileToProject("locales/fr/common.json", """{"menu":{}}""")

        val ok = viewModel.saveValue(project, "common:menu.home", "fr", "Accueil")

        Assertions.assertTrue(ok)
        Assertions.assertEquals("Accueil", valueAt("locales/fr/common.json", "menu", "home"))
        Assertions.assertEquals("Home", valueAt("locales/en/common.json", "menu", "home"), "other locales must be untouched")
    }

    @Test
    fun saveValue_returnsFalseWhenNoFileMatchesLocale() {
        addFileToProject("locales/en/common.json", """{"menu":{"home":"Home"}}""")

        val ok = viewModel.saveValue(project, "common:menu.home", "de", "Startseite")

        Assertions.assertFalse(ok)
        Assertions.assertEquals("Home", valueAt("locales/en/common.json", "menu", "home"), "nothing must be written")
    }

    @Test
    fun saveValue_returnsFalseWhenNamespaceUnknown() {
        addFileToProject("locales/en/common.json", """{"menu":{"home":"Home"}}""")

        val ok = viewModel.saveValue(project, "profile:bio", "en", "Bio")

        Assertions.assertFalse(ok)
    }

    // ── Module scoping ────────────────────────────────────────────────────────
    // Two modules owning a file with the same namespace and locale is the normal
    // monorepo case. Without a module scope the write resolves to whichever file
    // comes first project-wide, so an edit made in one module's table lands in
    // the other module's file.
    //
    // Module filtering matches ModuleConfig.rootDirectory against the source's
    // displayPath (relative to the project base). In this light fixture the project
    // files live under `src/`, so a module rooted at `mobile/` has the root directory
    // `src/mobile` here — in a real project it would simply be `mobile`.

    private fun moduleAt(name: String) = ModuleConfig(name = name, rootDirectory = "src/$name")

    @Test
    fun saveValue_withModuleScope_writesInThatModuleOnly() {
        addFileToProject("web/locales/en/common.json", """{"menu":{"home":"Home web"}}""")
        addFileToProject("mobile/locales/en/common.json", """{"menu":{"home":"Home mobile"}}""")

        val ok = viewModel.saveValue(project, "common:menu.home", "en", "Accueil mobile", moduleAt("mobile"))

        Assertions.assertTrue(ok)
        Assertions.assertEquals("Accueil mobile", valueAt("mobile/locales/en/common.json", "menu", "home"))
        Assertions.assertEquals(
            "Home web",
            valueAt("web/locales/en/common.json", "menu", "home"),
            "the other module's file must not be touched"
        )
    }

    @Test
    fun saveValue_withModuleScope_refusesWhenKeyIsNotInThatModule() {
        addFileToProject("web/locales/en/common.json", """{"menu":{"home":"Home web"}}""")

        val ok = viewModel.saveValue(project, "common:menu.home", "en", "Nope", moduleAt("mobile"))

        Assertions.assertFalse(ok, "no file in the mobile module: must refuse rather than fall back to web")
        Assertions.assertEquals("Home web", valueAt("web/locales/en/common.json", "menu", "home"))
    }
}
