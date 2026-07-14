package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Regression tests for [OrphanKeyDeleter] (Table View context menu):
 * it used to delete the VALUE element instead of the property (leaving a
 * dangling `"key":`) and to run one WriteCommandAction per locale.
 * Deletion must now remove the whole property plus its separating comma,
 * across every locale, leaving valid JSON behind.
 */
class OrphanKeyDeleterTest : PlatformBaseTest() {

    private fun fileText(path: String): String =
        ReadAction.compute<String, RuntimeException> {
            val vf = myFixture.findFileInTempDir(path)
            PsiManager.getInstance(project).findFile(vf)!!.text
        }

    private fun valueAt(path: String, vararg key: String): String? =
        ReadAction.compute<String?, RuntimeException> {
            val vf = myFixture.findFileInTempDir(path) ?: return@compute null
            val file = PsiManager.getInstance(project).findFile(vf) as? JsonFile ?: return@compute null
            var node = file.topLevelValue as? JsonObject ?: return@compute null
            for (i in 0 until key.size - 1) node = node.findProperty(key[i])?.value as? JsonObject ?: return@compute null
            (node.findProperty(key.last())?.value as? JsonStringLiteral)?.value
        }

    private fun delete(key: String) {
        OrphanKeyDeleter(project).delete(KeysSynchronizer().buildFullKey(key))
    }

    /**
     * In the light fixture, project files live under `src/`, so a module rooted at
     * `mobile/` has the root directory `src/mobile` here — in a real project it is
     * simply `mobile`.
     */
    private fun deleteInModule(key: String, module: String) {
        val config = ModuleConfig(name = module, rootDirectory = "src/$module")
        OrphanKeyDeleter(project, config).delete(KeysSynchronizer().buildFullKey(key))
    }

    @Test
    fun deleteScopedToModule_leavesOtherModulesUntouched() {
        addFileToProject("web/locales/en/common.json", """{"dead":"web","alive":"yes"}""")
        addFileToProject("mobile/locales/en/common.json", """{"dead":"mobile","alive":"oui"}""")

        deleteInModule("common:dead", "mobile")

        Assertions.assertNull(valueAt("mobile/locales/en/common.json", "dead"))
        Assertions.assertEquals(
            "web",
            valueAt("web/locales/en/common.json", "dead"),
            "the other module's file must not be touched"
        )
        Assertions.assertEquals("oui", valueAt("mobile/locales/en/common.json", "alive"))
    }

    @Test
    fun deletesWholePropertyNotJustValue_noDanglingKey() {
        addFileToProject("locales/en/common.json", """{"a":"1","b":"2","c":"3"}""")

        delete("common:b")

        val text = fileText("locales/en/common.json")
        Assertions.assertFalse(text.contains("\"b\""), "the property name must be gone, not just its value: $text")
        Assertions.assertEquals("1", valueAt("locales/en/common.json", "a"))
        Assertions.assertEquals("3", valueAt("locales/en/common.json", "c"))
    }

    @Test
    fun deletesFirstAndLastPropertyKeepingFileValid() {
        addFileToProject("locales/en/first.json", """{"a":"1","b":"2"}""")
        addFileToProject("locales/en/last.json", """{"x":"9","y":"8"}""")

        delete("first:a")
        delete("last:y")

        Assertions.assertEquals("2", valueAt("locales/en/first.json", "b"))
        Assertions.assertNull(valueAt("locales/en/first.json", "a"))
        Assertions.assertEquals("9", valueAt("locales/en/last.json", "x"))
        Assertions.assertNull(valueAt("locales/en/last.json", "y"))
    }

    @Test
    fun deletesAcrossAllLocales() {
        addFileToProject("locales/en/common.json", """{"dead":"gone","alive":"yes"}""")
        addFileToProject("locales/fr/common.json", """{"dead":"parti","alive":"oui"}""")

        delete("common:dead")

        Assertions.assertNull(valueAt("locales/en/common.json", "dead"))
        Assertions.assertNull(valueAt("locales/fr/common.json", "dead"))
        Assertions.assertEquals("yes", valueAt("locales/en/common.json", "alive"))
        Assertions.assertEquals("oui", valueAt("locales/fr/common.json", "alive"))
    }
}
