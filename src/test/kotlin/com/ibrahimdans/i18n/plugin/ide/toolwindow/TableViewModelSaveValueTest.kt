package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
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
}
