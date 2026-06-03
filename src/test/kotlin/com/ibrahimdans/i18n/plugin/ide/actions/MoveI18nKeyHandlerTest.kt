package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Exercises the real [MoveI18nKeyHandler.execute] write logic against on-disk PSI:
 * value copy across locales, creation of a missing target file (no data loss),
 * source deletion, and code-reference rewriting.
 *
 * Note: this test extends [PlatformBaseTest] (→ junit.framework.TestCase), whose inherited
 * assertion methods would shadow the static JUnit Jupiter imports — so assertions are
 * qualified with [Assertions] to bind to Jupiter (and avoid the reversed assertNull arg order).
 */
class MoveI18nKeyHandlerTest : PlatformBaseTest() {

    private val handler = MoveI18nKeyHandler()

    private fun literals(vararg parts: String) = parts.map { Literal(it) }

    private fun jsonFile(path: String): JsonFile {
        val vf = myFixture.findFileInTempDir(path)
        return PsiManager.getInstance(project).findFile(vf) as JsonFile
    }

    /** Resolves a nested "a.b.c" property's string-literal value node in a JSON file. */
    private fun leaf(file: JsonFile, vararg path: String): JsonStringLiteral {
        var obj = file.topLevelValue as JsonObject
        for (i in 0 until path.size - 1) {
            obj = obj.findProperty(path[i])!!.value as JsonObject
        }
        return obj.findProperty(path.last())!!.value as JsonStringLiteral
    }

    private fun valueAt(path: String, vararg key: String): String? =
        ReadAction.compute<String?, RuntimeException> {
            val obj = jsonFile(path).topLevelValue as? JsonObject ?: return@compute null
            var node: JsonObject? = obj
            for (i in 0 until key.size - 1) node = node?.findProperty(key[i])?.value as? JsonObject
            (node?.findProperty(key.last())?.value as? JsonStringLiteral)?.value
        }

    @Test
    fun execute_singleLocale_copiesValueAndDeletesSource() {
        addFileToProject("locales/en/common.json", """{"user":{"name":"John"}}""")
        addFileToProject("locales/en/profile.json", """{}""")

        WriteCommandAction.runWriteCommandAction(project) {
            val src = leaf(jsonFile("locales/en/common.json"), "user", "name")
            handler.execute(project, listOf(src), emptyList(), literals("user", "name"), "profile")
        }

        Assertions.assertEquals("John", valueAt("locales/en/profile.json", "user", "name"))
        Assertions.assertNull(valueAt("locales/en/common.json", "user", "name"), "source entry should be deleted")
    }

    @Test
    fun execute_multiLocale_copiesEachLocale() {
        addFileToProject("locales/en/common.json", """{"greeting":"Hello"}""")
        addFileToProject("locales/fr/common.json", """{"greeting":"Bonjour"}""")
        addFileToProject("locales/en/ui.json", """{}""")
        addFileToProject("locales/fr/ui.json", """{}""")

        WriteCommandAction.runWriteCommandAction(project) {
            val leaves = listOf(
                leaf(jsonFile("locales/en/common.json"), "greeting"),
                leaf(jsonFile("locales/fr/common.json"), "greeting"),
            )
            handler.execute(project, leaves, emptyList(), literals("greeting"), "ui")
        }

        Assertions.assertEquals("Hello", valueAt("locales/en/ui.json", "greeting"))
        Assertions.assertEquals("Bonjour", valueAt("locales/fr/ui.json", "greeting"))
        Assertions.assertNull(valueAt("locales/en/common.json", "greeting"))
        Assertions.assertNull(valueAt("locales/fr/common.json", "greeting"))
    }

    @Test
    fun execute_targetFileMissing_isCreatedSoNoLocaleIsDropped() {
        // fr has the key in common but there is NO fr/profile.json target file.
        addFileToProject("locales/en/common.json", """{"greeting":"Hello"}""")
        addFileToProject("locales/fr/common.json", """{"greeting":"Bonjour"}""")
        addFileToProject("locales/en/profile.json", """{}""")
        // intentionally no locales/fr/profile.json

        WriteCommandAction.runWriteCommandAction(project) {
            val leaves = listOf(
                leaf(jsonFile("locales/en/common.json"), "greeting"),
                leaf(jsonFile("locales/fr/common.json"), "greeting"),
            )
            handler.execute(project, leaves, emptyList(), literals("greeting"), "profile")
        }

        // The fr target file must have been created and populated — no silent data loss.
        Assertions.assertNotNull(myFixture.findFileInTempDir("locales/fr/profile.json"), "fr target file should be created")
        Assertions.assertEquals("Hello", valueAt("locales/en/profile.json", "greeting"))
        Assertions.assertEquals("Bonjour", valueAt("locales/fr/profile.json", "greeting"))
        Assertions.assertNull(valueAt("locales/fr/common.json", "greeting"))
    }

    @Test
    fun execute_collision_overwritesTargetValue() {
        addFileToProject("locales/en/common.json", """{"title":"Common Title"}""")
        addFileToProject("locales/en/ui.json", """{"title":"Old UI Title"}""")

        WriteCommandAction.runWriteCommandAction(project) {
            val src = leaf(jsonFile("locales/en/common.json"), "title")
            handler.execute(project, listOf(src), emptyList(), literals("title"), "ui")
        }

        Assertions.assertEquals("Common Title", valueAt("locales/en/ui.json", "title"))
        Assertions.assertNull(valueAt("locales/en/common.json", "title"))
    }

    @Test
    fun execute_rewritesCodeReferences_explicitAndImplicitNamespace() {
        addFileToProject("locales/en/common.json", """{"greeting":"Hello"}""")
        addFileToProject("locales/en/ui.json", """{}""")
        // Two usages: one with explicit ns prefix, one implicit (hook-style, no prefix).
        val code = addFileToProject(
            "src/App.tsx",
            "const a = t('common:greeting'); const b = t('greeting');"
        )

        val codeUsages = ReadAction.compute<List<PsiElement>, RuntimeException> {
            collectQuotedLiterals(code, "common:greeting", "greeting")
        }
        // Guard: the test is only meaningful if the JS literals are resolvable in the sandbox.
        Assertions.assertEquals(2, codeUsages.size, "expected to locate both code literals")

        WriteCommandAction.runWriteCommandAction(project) {
            val src = leaf(jsonFile("locales/en/common.json"), "greeting")
            handler.execute(project, listOf(src), codeUsages, literals("greeting"), "ui")
        }

        // Read back via the document (the rewrite edits the document directly).
        val updated = ReadAction.compute<String, RuntimeException> {
            val vf = myFixture.findFileInTempDir("src/App.tsx")
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)!!.text
        }
        // Both literals carry an explicit target-namespace prefix after the move.
        Assertions.assertEquals("const a = t('ui:greeting'); const b = t('ui:greeting');", updated)
    }

    @Test
    fun buildContext_returnsNull_whenNoKeyAtCaret() {
        myFixture.configureByText("plain.ts", "const x = <caret>42")
        val ctx = ReadAction.compute<MoveI18nKeyHandler.MoveContext?, RuntimeException> {
            handler.buildContext(myFixture.editor, myFixture.file)
        }
        Assertions.assertNull(ctx)
    }

    /**
     * Collects the smallest leaf-ish PSI elements whose text is a quoted literal equal to one of [texts].
     * Picks the deepest matching element so we target the literal itself, not an enclosing expression.
     */
    private fun collectQuotedLiterals(file: PsiElement, vararg texts: String): List<PsiElement> {
        val wanted = texts.toSet()
        val result = mutableListOf<PsiElement>()
        file.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                val t = element.text
                if (t.length > 1 && (t[0] == '\'' || t[0] == '"') &&
                    t.last() == t[0] && t.substring(1, t.length - 1) in wanted &&
                    element.parent?.text != t // topmost element holding exactly this quoted literal
                ) {
                    result.add(element)
                }
            }
        })
        return result
    }
}
