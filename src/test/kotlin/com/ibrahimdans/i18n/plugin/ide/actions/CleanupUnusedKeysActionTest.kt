package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
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

    /**
     * With `common` as the default namespace, `menu.home` is common's key and `auth:menu.home`
     * another one, on its own row: deleting the first used to delete the second with it.
     */
    @Test
    fun deleteKeys_leavesTheHomonymOfAnotherNamespaceAlone() = myFixture.runWithConfig(Config(defaultNs = "common")) {
        addFileToProject("locales/en/auth.json", """{"menu":{"home":"Sign-in home"}}""")
        addFileToProject("locales/en/common.json", """{"menu":{"home":"Home"}}""")

        Assertions.assertEquals(1, action.deleteKeys(project, listOf("menu.home")), "one locale entry, common's")

        Assertions.assertNull(valueAt("locales/en/common.json", "menu", "home"))
        Assertions.assertEquals("Sign-in home", valueAt("locales/en/auth.json", "menu", "home"), "auth's key is not the one deleted")
    }

    @Test
    fun deleteKeys_multipleKeysInOnePass() {
        addFileToProject("locales/en/common.json", """{"a":"1","b":"2","c":"3"}""")

        action.deleteKeys(project, listOf("common:a", "common:c"))

        Assertions.assertNull(valueAt("locales/en/common.json", "a"))
        Assertions.assertEquals("2", valueAt("locales/en/common.json", "b"))
        Assertions.assertNull(valueAt("locales/en/common.json", "c"))
    }

    /**
     * The file left by deleting several keys, neighbours or not, in any order: commas, whitespace and
     * blank lines exactly as deleting the properties one by one left them before the deletion went
     * by runs of neighbouring properties (TASK-WRITE-COMMAND-COST). Expected texts captured from the
     * one-by-one deletion, odd shapes included.
     */
    @Test
    fun deleteKeys_leavesTheSameFileAsDeletingOneByOne() {
        SHAPES.forEachIndexed { index, (content, keys, expected) ->
            val path = "locales/en/shape$index.json"
            addFileToProject(path, content)
            action.deleteKeys(project, keys.map { "shape$index:$it" })
            Assertions.assertEquals(expected, textOf(path), "shape $index, deleting $keys")
        }
    }

    private fun textOf(path: String): String =
        FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(path))!!.text

    private companion object {
        const val PRETTY = "{\n  \"a\": \"1\",\n  \"b\": \"2\",\n  \"c\": \"3\",\n  \"d\": \"4\"\n}"
        const val NESTED = "{\n  \"menu\": {\n    \"a\": \"1\",\n    \"b\": \"2\"\n  },\n  \"x\": \"y\"\n}"
        const val COMPACT = "{\"a\":\"1\",\"b\":\"2\",\"c\":\"3\"}"

        /** (file, keys deleted in that order, file left). */
        val SHAPES = listOf(
            Triple(PRETTY, listOf("b", "c"), "{\n  \"a\": \"1\",\n  \"d\": \"4\"\n}"),
            Triple(PRETTY, listOf("a", "b"), "{\n  \"c\": \"3\",\n  \"d\": \"4\"\n}"),
            Triple(PRETTY, listOf("c", "d"), "{\n  \"a\": \"1\",\n  \"b\": \"2\"\n}"),
            Triple(PRETTY, listOf("a", "c"), "{\n  \"b\": \"2\",\n  \"d\": \"4\"\n}"),
            Triple(PRETTY, listOf("d", "b", "c"), "{\n  \"a\": \"1\"\n}"),
            Triple(PRETTY, listOf("a", "b", "c", "d"), "{\n}"),
            Triple(NESTED, listOf("menu.a", "menu.b"), "{\n  \"menu\": {\n  },\n  \"x\": \"y\"\n}"),
            Triple(NESTED, listOf("menu.b", "x"), "{\n  \"menu\": {\n    \"a\": \"1\"\n  }\n}"),
            Triple(COMPACT, listOf("a", "b"), "{\n  \"c\":\"3\"}"),
            Triple(COMPACT, listOf("b", "c"), "{\"a\":\"1\"}"),
            Triple(
                "{\n  \"a\": \"1\", \"b\": \"2\",\n\n  \"c\": {\"d\": \"4\"},\n  \"e\": \"5\"\n}",
                listOf("b", "c.d", "e"),
                "{\n  \"a\": \"1\",\n\n  \"c\": {}\n}",
            ),
        )
    }
}
