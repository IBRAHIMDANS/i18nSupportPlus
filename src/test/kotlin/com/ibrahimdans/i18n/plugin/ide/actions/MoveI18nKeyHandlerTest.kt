package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.actionSystem.ActionUpdateThread
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

    // Every fixture above holds a single key in the source object, so deleting it never
    // leaves a separator behind — which is exactly why the source file was silently being
    // corrupted whenever the moved key had a sibling (the normal case).
    //
    // These assertions look at the raw file text on purpose: the JSON PSI is error-tolerant
    // and still resolves the siblings of a `{"a":"1",,"c":"3"}` object, so asserting on
    // resolved values alone would pass on a corrupted file.

    private fun textOf(path: String): String =
        ReadAction.compute<String, RuntimeException> { jsonFile(path).text }

    private fun assertNoDanglingSeparator(path: String) {
        val text = textOf(path).filterNot { it.isWhitespace() }
        Assertions.assertFalse(text.contains(",,"), "double comma left behind in $path: $text")
        Assertions.assertFalse(text.contains("{,"), "leading comma left behind in $path: $text")
        Assertions.assertFalse(text.contains(",}"), "trailing comma left behind in $path: $text")
    }

    @Test
    fun execute_middleKeyWithSiblings_leavesSourceFileValid() {
        addFileToProject("locales/en/common.json", """{"user":{"first":"John","name":"Doe","last":"Smith"}}""")
        addFileToProject("locales/en/profile.json", """{}""")

        WriteCommandAction.runWriteCommandAction(project) {
            val src = leaf(jsonFile("locales/en/common.json"), "user", "name")
            handler.execute(project, listOf(src), emptyList(), literals("user", "name"), "profile")
        }

        Assertions.assertEquals("Doe", valueAt("locales/en/profile.json", "user", "name"))
        Assertions.assertNull(valueAt("locales/en/common.json", "user", "name"))
        assertNoDanglingSeparator("locales/en/common.json")
    }

    @Test
    fun execute_firstKeyWithSibling_leavesSourceFileValid() {
        addFileToProject("locales/en/common.json", """{"a":"1","b":"2"}""")
        addFileToProject("locales/en/target.json", """{}""")

        WriteCommandAction.runWriteCommandAction(project) {
            handler.execute(
                project,
                listOf(leaf(jsonFile("locales/en/common.json"), "a")),
                emptyList(), literals("a"), "target"
            )
        }

        Assertions.assertEquals("2", valueAt("locales/en/common.json", "b"))
        assertNoDanglingSeparator("locales/en/common.json")
    }

    @Test
    fun execute_lastKeyWithSibling_leavesSourceFileValid() {
        addFileToProject("locales/en/common.json", """{"x":"9","y":"8"}""")
        addFileToProject("locales/en/target.json", """{}""")

        WriteCommandAction.runWriteCommandAction(project) {
            handler.execute(
                project,
                listOf(leaf(jsonFile("locales/en/common.json"), "y")),
                emptyList(), literals("y"), "target"
            )
        }

        Assertions.assertEquals("9", valueAt("locales/en/common.json", "x"))
        assertNoDanglingSeparator("locales/en/common.json")
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
    fun execute_safetyGuard_doesNotRewriteUnrelatedLiteral() {
        addFileToProject("locales/en/common.json", """{"greeting":"Hello"}""")
        addFileToProject("locales/en/ui.json", """{}""")
        // One literal carries the moved key; the other is unrelated and must stay untouched
        // even if it is (defensively) handed to execute() as a code usage.
        val code = addFileToProject(
            "src/App.tsx",
            "const a = t('common:greeting'); const b = t('unrelated:other');"
        )

        val usages = ReadAction.compute<List<PsiElement>, RuntimeException> {
            collectQuotedLiterals(code, "common:greeting", "unrelated:other")
        }
        Assertions.assertEquals(2, usages.size, "expected to locate both code literals")

        WriteCommandAction.runWriteCommandAction(project) {
            val src = leaf(jsonFile("locales/en/common.json"), "greeting")
            handler.execute(project, listOf(src), usages, literals("greeting"), "ui")
        }

        val updated = ReadAction.compute<String, RuntimeException> {
            val vf = myFixture.findFileInTempDir("src/App.tsx")
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)!!.text
        }
        // Only the matching key was rewritten; the unrelated literal is left intact.
        Assertions.assertEquals("const a = t('ui:greeting'); const b = t('unrelated:other');", updated)
    }

    @Test
    fun resolveKey_returnsNull_whenNoKeyAtCaret() {
        myFixture.configureByText("plain.ts", "const x = <caret>42")
        val ctx = ReadAction.compute<MoveI18nKeyHandler.ResolvedKey?, RuntimeException> {
            handler.resolveKey(myFixture.editor, myFixture.file)
        }
        Assertions.assertNull(ctx)
    }

    @Test
    fun resolveKey_implicitNamespaceFromHook_resolvesSingleSource() {
        addFileToProject("assets/dashboard.json", """{"main":{"title":"Dashboard Title"}}""")
        myFixture.configureByText(
            "Comp.tsx",
            """
            import { useTranslation } from 'react-i18next';
            export default function Component() {
                const { t } = useTranslation('dashboard');
                return t('main.title<caret>');
            }
            """.trimIndent()
        )
        val resolved = ReadAction.compute<MoveI18nKeyHandler.ResolvedKey?, RuntimeException> {
            handler.resolveKey(myFixture.editor, myFixture.file)
        }
        Assertions.assertNotNull(resolved)
        // Namespace derived from the resolved file, even though the literal has no "dashboard:" prefix.
        Assertions.assertEquals(setOf("dashboard"), resolved!!.leavesByNamespace.keys)
        Assertions.assertEquals(listOf("main", "title"), resolved.compositeKey.map { it.text })
    }

    @Test
    fun resolveKey_sameKeyInMultipleHookNamespaces_groupsByNamespace() {
        // Same composite key present in BOTH namespaces of a useTranslation(['a','b']) hook.
        addFileToProject("assets/dashboard.json", """{"shared":{"label":"From Dashboard"}}""")
        addFileToProject("assets/common.json", """{"shared":{"label":"From Common"}}""")
        myFixture.configureByText(
            "Comp.tsx",
            """
            import { useTranslation } from 'react-i18next';
            export default function Component() {
                const { t } = useTranslation(['dashboard', 'common']);
                return t('shared.label<caret>');
            }
            """.trimIndent()
        )
        val resolved = ReadAction.compute<MoveI18nKeyHandler.ResolvedKey?, RuntimeException> {
            handler.resolveKey(myFixture.editor, myFixture.file)
        }
        Assertions.assertNotNull(resolved)
        // Both namespaces are surfaced so the action can ask which one to move (no silent pick).
        Assertions.assertEquals(setOf("dashboard", "common"), resolved!!.leavesByNamespace.keys)
    }

    // The key was recomposed with `.` and `:` whatever the settings said, and the call sites were
    // told apart by a textual `endsWith(":user.name")`: with other separators nothing matched, and
    // what was written back was a key the parser would not read.

    private fun documentText(path: String): String = ReadAction.compute<String, RuntimeException> {
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(path))!!.text
    }

    private val slashAndPipe = Config(nsSeparator = "|", keySeparator = "/")

    @Test
    fun execute_customSeparators_rewritesTheCallsWithThem() = myFixture.runWithConfig(slashAndPipe) {
        addFileToProject("locales/en/common.json", """{"user":{"name":"John","age":"Age"}}""")
        addFileToProject("locales/en/profile.json", """{}""")
        val code = addFileToProject("src/App.tsx", "const a = t('common|user/name'); const b = t('user/name'); const c = t('common|user/age');")

        val usages = ReadAction.compute<List<PsiElement>, RuntimeException> {
            collectQuotedLiterals(code, "common|user/name", "user/name", "common|user/age")
        }
        Assertions.assertEquals(3, usages.size, "expected to locate the three code literals")

        WriteCommandAction.runWriteCommandAction(project) {
            val src = leaf(jsonFile("locales/en/common.json"), "user", "name")
            handler.execute(project, listOf(src), usages, literals("user", "name"), "profile")
        }

        Assertions.assertEquals("John", valueAt("locales/en/profile.json", "user", "name"))
        Assertions.assertNull(valueAt("locales/en/common.json", "user", "name"))
        Assertions.assertEquals(
            "const a = t('profile|user/name'); const b = t('profile|user/name'); const c = t('common|user/age');",
            documentText("src/App.tsx")
        )
    }

    @Test
    fun resolveKey_customSeparators_readsTheKeyAndItsNamespace() = myFixture.runWithConfig(slashAndPipe) {
        addFileToProject("assets/common.json", """{"user":{"name":"John"}}""")
        myFixture.configureByText("Comp.tsx", "const a = t('common|user/na<caret>me');")

        val resolved = ReadAction.compute<MoveI18nKeyHandler.ResolvedKey?, RuntimeException> {
            handler.resolveKey(myFixture.editor, myFixture.file)
        }

        Assertions.assertNotNull(resolved)
        Assertions.assertEquals(setOf("common"), resolved!!.leavesByNamespace.keys)
        Assertions.assertEquals(listOf("user", "name"), resolved.compositeKey.map { it.text })
    }

    /** A flat key is one property whatever it contains, and no namespace can be written in front of it. */
    @Test
    fun execute_flatKeys_movesTheWholePropertyAndLeavesTheCallAsWritten() = myFixture.runWithConfig(Config(flatKeys = true)) {
        addFileToProject("locales/en/common.json", """{"app.title":"Title","app.other":"Other"}""")
        addFileToProject("locales/en/ui.json", """{}""")
        val code = addFileToProject("src/App.tsx", "const a = t('app.title');")
        val usages = ReadAction.compute<List<PsiElement>, RuntimeException> { collectQuotedLiterals(code, "app.title") }
        Assertions.assertEquals(1, usages.size)

        WriteCommandAction.runWriteCommandAction(project) {
            val src = leaf(jsonFile("locales/en/common.json"), "app.title")
            handler.execute(project, listOf(src), usages, literals("app.title"), "ui")
        }

        Assertions.assertEquals("Title", valueAt("locales/en/ui.json", "app.title"), "one flat property, not an `app` object")
        Assertions.assertNull(valueAt("locales/en/ui.json", "app", "title"))
        Assertions.assertNull(valueAt("locales/en/common.json", "app.title"))
        Assertions.assertEquals("Other", valueAt("locales/en/common.json", "app.other"))
        Assertions.assertEquals("const a = t('app.title');", documentText("src/App.tsx"))
    }

    /** In a folder-per-namespace module the target is the file the template designates, not a sibling `profile.json`. */
    @Test
    fun execute_moduleTemplate_writesWhereTheTemplateSays() = myFixture.runWithConfig(
        Config(modules = listOf(ModuleConfig(name = "app", pathTemplate = "locales/{ns}/{lang}.json")))
    ) {
        addFileToProject("locales/common/en.json", """{"greeting":"Hello","other":"Other"}""")
        addFileToProject("locales/common/fr.json", """{"greeting":"Bonjour","other":"Autre"}""")
        addFileToProject("locales/profile/en.json", """{"title":"Profile"}""")
        // intentionally no locales/profile/fr.json

        WriteCommandAction.runWriteCommandAction(project) {
            val leaves = listOf(
                leaf(jsonFile("locales/common/en.json"), "greeting"),
                leaf(jsonFile("locales/common/fr.json"), "greeting"),
            )
            handler.execute(project, leaves, emptyList(), literals("greeting"), "profile")
        }

        Assertions.assertEquals("Hello", valueAt("locales/profile/en.json", "greeting"))
        Assertions.assertEquals("Profile", valueAt("locales/profile/en.json", "title"), "the existing target file is kept")
        Assertions.assertNotNull(myFixture.findFileInTempDir("locales/profile/fr.json"), "the fr target file is created by the template")
        Assertions.assertEquals("Bonjour", valueAt("locales/profile/fr.json", "greeting"))
        Assertions.assertNull(myFixture.findFileInTempDir("locales/common/profile.json"), "no sibling file beside the source")
        Assertions.assertNull(valueAt("locales/common/en.json", "greeting"))
        Assertions.assertNull(valueAt("locales/common/fr.json", "greeting"))
    }

    /** The namespace of a resource bundle is the one its format states, not the file stem (`messages_fr`). */
    @Test
    fun resolveKey_propertiesBundle_groupsEveryLocaleUnderTheStatedNamespace() {
        addFileToProject("src/main/resources/messages.properties", "user.name=Name\n")
        addFileToProject("src/main/resources/messages_fr.properties", "user.name=Nom\n")
        addFileToProject("src/main/resources/errors.properties", "failed=Failed\n")
        addFileToProject("src/main/resources/errors_fr.properties", "failed=Echec\n")
        myFixture.configureByText("Comp.tsx", "const a = t('messages:user.na<caret>me');")

        val resolved = ReadAction.compute<MoveI18nKeyHandler.ResolvedKey?, RuntimeException> {
            handler.resolveKey(myFixture.editor, myFixture.file)
        }

        Assertions.assertNotNull(resolved, "the bundle must resolve the key")
        Assertions.assertEquals(setOf("messages"), resolved!!.leavesByNamespace.keys)
        Assertions.assertEquals(2, resolved.leavesByNamespace.getValue("messages").size, "both locales under one namespace")
    }

    /** The `resources` of an i18next configuration are named after their locale: they hold the default namespace. */
    @Test
    fun resolveKey_i18nextInlineResources_holdTheDefaultNamespace() = myFixture.runWithConfig(Config(jsConfiguration = "src/i18n.ts")) {
        addFileToProject(
            "src/i18n.ts",
            """
            i18n.init({
              resources: {
                en: { translation: { greeting: 'Hello' } },
                fr: { translation: { greeting: 'Bonjour' } },
              },
            });
            """.trimIndent()
        )
        myFixture.configureByText("Comp.tsx", "const a = t('greet<caret>ing');")

        val resolved = ReadAction.compute<MoveI18nKeyHandler.ResolvedKey?, RuntimeException> {
            handler.resolveKey(myFixture.editor, myFixture.file)
        }

        Assertions.assertNotNull(resolved, "the inline resources must resolve the key")
        Assertions.assertEquals(setOf("translation"), resolved!!.leavesByNamespace.keys)
        Assertions.assertEquals(2, resolved.leavesByNamespace.getValue("translation").size, "both locales under one namespace")
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

    /** `update` reads PSI_FILE, which the platform refuses on the EDT; the flag must stay BGT. */
    @Test
    fun updateRunsInBackground() {
        Assertions.assertEquals(ActionUpdateThread.BGT, handler.actionUpdateThread)
    }
}
