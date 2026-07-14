package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
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
