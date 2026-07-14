package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Exercises the core of [CleanupUnusedKeysAction] against real PSI:
 * leaf resolution across locales, multi-locale deletion in one pass,
 * and the safety net that only deletes valid resolved properties.
 */
class CleanupUnusedKeysActionTest : PlatformBaseTest() {

    private val action = CleanupUnusedKeysAction()

    /** Sources are now loaded once by the caller and passed in (they used to be re-scanned per key). */
    private fun sources() =
        project.getService(com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService::class.java)
            .findAllSources(project)

    private fun valueAt(path: String, vararg key: String): String? =
        ReadAction.compute<String?, RuntimeException> {
            val vf = myFixture.findFileInTempDir(path) ?: return@compute null
            val file = PsiManager.getInstance(project).findFile(vf) as? JsonFile ?: return@compute null
            var node = file.topLevelValue as? JsonObject ?: return@compute null
            for (i in 0 until key.size - 1) node = node.findProperty(key[i])?.value as? JsonObject ?: return@compute null
            (node.findProperty(key.last())?.value as? JsonStringLiteral)?.value
        }

    @Test
    fun leafProperties_resolvesOnePropertyPerLocale() {
        addFileToProject("locales/en/common.json", """{"menu":{"home":"Home"}}""")
        addFileToProject("locales/fr/common.json", """{"menu":{"home":"Accueil"}}""")

        val leaves = ReadAction.compute<List<*>, RuntimeException> {
            action.leafProperties(sources(), "common:menu.home")
        }

        Assertions.assertEquals(2, leaves.size, "one property per locale")
    }

    @Test
    fun leafProperties_ignoresLocalesWhereKeyIsMissing() {
        addFileToProject("locales/en/common.json", """{"menu":{"home":"Home"}}""")
        addFileToProject("locales/fr/common.json", """{"menu":{}}""")

        val leaves = ReadAction.compute<List<*>, RuntimeException> {
            action.leafProperties(sources(), "common:menu.home")
        }

        Assertions.assertEquals(1, leaves.size)
    }

    @Test
    fun deleteKeys_removesKeyFromEveryLocale() {
        addFileToProject("locales/en/common.json", """{"menu":{"home":"Home","exit":"Exit"}}""")
        addFileToProject("locales/fr/common.json", """{"menu":{"home":"Accueil","exit":"Quitter"}}""")

        action.deleteKeys(project, listOf("common:menu.exit"))

        Assertions.assertNull(valueAt("locales/en/common.json", "menu", "exit"))
        Assertions.assertNull(valueAt("locales/fr/common.json", "menu", "exit"))
        Assertions.assertEquals("Home", valueAt("locales/en/common.json", "menu", "home"), "sibling keys must survive")
        Assertions.assertEquals("Accueil", valueAt("locales/fr/common.json", "menu", "home"))
    }

    @Test
    fun deleteKeys_multipleKeysInOnePass() {
        addFileToProject("locales/en/common.json", """{"a":"1","b":"2","c":"3"}""")

        action.deleteKeys(project, listOf("common:a", "common:c"))

        Assertions.assertNull(valueAt("locales/en/common.json", "a"))
        Assertions.assertEquals("2", valueAt("locales/en/common.json", "b"))
        Assertions.assertNull(valueAt("locales/en/common.json", "c"))
    }

    @Test
    fun hasPsiReferences_falseForKeyNeverUsedInCode() {
        addFileToProject("locales/en/common.json", """{"dead":{"key":"never used"}}""")

        val referenced = ReadAction.compute<Boolean, RuntimeException> {
            action.hasPsiReferences(sources(), "common:dead.key")
        }

        Assertions.assertFalse(referenced)
    }
}
