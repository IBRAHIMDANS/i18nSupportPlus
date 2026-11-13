package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.IdeKeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.actions.RenameI18nKeyHandler
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nReference
import com.intellij.concurrency.JobLauncher
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * `t(SAVE_KEY)`, `t(KEYS.save)` and `t(Keys.Save)`: a key held by a constant, one hop away from the
 * call, is read as if the call wrote it. Before, none of them was annotated nor navigable.
 */
class ConstantKeyResolverTest : PlatformBaseTest() {

    private fun translations() =
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save", "cancel": "Cancel"}}""")

    /** The text of each range reported as an unresolved key in the open file. */
    private fun unresolved(): List<String> {
        val text = myFixture.file.text
        return myFixture.doHighlighting()
            .filter { it.description == "Unresolved key" }
            .map { text.substring(it.startOffset, it.endOffset) }
            .sorted()
    }

    private fun literal(text: String): JSLiteralExpression =
        PsiTreeUtil.findChildrenOfType(myFixture.file, JSLiteralExpression::class.java).single { it.text == text }

    private fun keyReferenceOf(element: JSLiteralExpression): I18nReference? =
        element.references.filterIsInstance<I18nReference>().firstOrNull()

    @Test
    fun `a local constant is read at the call`() {
        translations()
        myFixture.configureByText(
            "Local.ts",
            """
            const SAVE_KEY = 'common:button.save';
            const MISSING_KEY = `common:button.absent`;
            export const a = (t) => [t(SAVE_KEY), t(MISSING_KEY)];
            """.trimIndent()
        )

        Assertions.assertEquals(listOf("MISSING_KEY"), unresolved())
    }

    @Test
    fun `an imported constant and an as const object are read at the call`() {
        translations()
        addFileToProject(
            "keys.ts",
            """
            export const SAVE_KEY = 'common:button.absent';
            export const KEYS = { save: 'common:button.save', nested: { missing: 'common:button.absent' } } as const;
            """.trimIndent()
        )
        myFixture.configureByText(
            "Form.ts",
            """
            import { KEYS, SAVE_KEY } from './keys';
            export const a = (t) => [t(KEYS.save), t(KEYS.nested.missing), t(SAVE_KEY)];
            """.trimIndent()
        )

        Assertions.assertEquals(listOf("KEYS.nested.missing", "SAVE_KEY"), unresolved())
    }

    @Test
    fun `a constant re-exported by a barrel is read at the call`() {
        translations()
        addFileToProject("keys.ts", "export const KEYS = { save: 'common:button.save', missing: 'common:button.absent' } as const;")
        addFileToProject("index.ts", "export { KEYS } from './keys';")
        myFixture.configureByText(
            "Form.ts",
            """
            import { KEYS } from './index';
            export const a = (t) => [t(KEYS.save), t(KEYS.missing)];
            """.trimIndent()
        )

        Assertions.assertEquals(listOf("KEYS.missing"), unresolved())
    }

    @Test
    fun `an enum member is read at the call`() {
        translations()
        myFixture.configureByText(
            "Keys.ts",
            """
            enum Keys { Save = 'common:button.save', Missing = 'common:button.absent' }
            export const a = (t) => [t(Keys.Save), t(Keys.Missing)];
            """.trimIndent()
        )

        Assertions.assertEquals(listOf("Keys.Missing"), unresolved())
    }

    @Test
    fun `a reassigned variable, a parameter and a computed value stay unread`() {
        translations()
        myFixture.configureByText(
            "Ignored.ts",
            """
            let reassigned = 'common:button.absent';
            reassigned = 'common:button.other';
            let once = 'common:button.absent';
            const computed = (k: string) => k;
            function withDefault(t, k = 'common:button.absent') { return t(k); }
            export const a = (t, key: string) => [t(reassigned), t(key), t(computed('x')), t(once)];
            """.trimIndent()
        )

        Assertions.assertEquals(listOf("once"), unresolved(), "only the `let` never reassigned is read")
    }

    @Test
    fun `each branch of a conditional constant is annotated and navigable`() {
        translations()
        myFixture.configureByText(
            "Title.ts",
            """
            export const Title = (t, status: number) => {
                const titleKey = status === 401 ? 'common:button.save' : 'common:button.absent';
                return t(titleKey);
            };
            """.trimIndent()
        )

        val reported = unresolved()
        Assertions.assertEquals(1, reported.size, reported.toString())
        Assertions.assertTrue(reported.single().contains("absent"), reported.toString())
        val reference = keyReferenceOf(literal("'common:button.save'"))
        Assertions.assertNotNull(reference, "the resolved branch carries the key's reference")
        Assertions.assertTrue(reference!!.multiResolve(false).isNotEmpty())
    }

    @Test
    fun `ctrl+click on the constant opens the translation, its qualifier keeps the JavaScript navigation`() {
        translations()
        addFileToProject("keys.ts", "export const KEYS = { save: 'common:button.save' } as const;")
        myFixture.configureByText(
            "Form.ts",
            """
            import { KEYS } from './keys';
            export const a = (t) => t(KEYS.sa<caret>ve);
            """.trimIndent()
        )
        val handler = ConstantKeyGotoDeclarationHandler()
        val onName = myFixture.file.findElementAt(myFixture.caretOffset)
        val targets = ReadAction.compute<Array<*>?, RuntimeException> {
            handler.getGotoDeclarationTargets(onName, myFixture.caretOffset, myFixture.editor)
        }

        Assertions.assertEquals("common.json", (targets?.single() as? com.intellij.psi.PsiElement)?.containingFile?.name)
        val onQualifier = myFixture.file.findElementAt(myFixture.file.text.indexOf("KEYS.save"))
        Assertions.assertNull(handler.getGotoDeclarationTargets(onQualifier, 0, myFixture.editor))
    }

    @Test
    fun `the constant's literal carries the key's reference and counts as a usage`() {
        translations()
        myFixture.configureByText(
            "Keys.ts",
            """
            export const KEYS = { save: 'common:button.save', unrelated: 'common:button.cancel' } as const;
            export const a = (t) => t(KEYS.save);
            """.trimIndent()
        )

        Assertions.assertNotNull(keyReferenceOf(literal("'common:button.save'")))
        Assertions.assertNull(keyReferenceOf(literal("'common:button.cancel'")), "a constant no call passes is no key")
        Assertions.assertEquals(1, IdeKeyUsageIndex(project).usages("common:button.save").size)
    }

    @Test
    fun `a rename rewrites the constant's literal once and leaves its calls`() {
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save"}}""")
        addFileToProject("keys.ts", "export const KEYS = { save: 'common:button.save' } as const;")
        addFileToProject("Form.ts", "import { KEYS } from './keys';\nexport const a = (t) => [t(KEYS.save), t(KEYS.save)];")
        myFixture.configureByText("Other.js", "export const b = (t) => t('common:button.sa<caret>ve');")
        setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = "store"
            override fun show(message: String, validator: InputValidator?): String = "store"
        })

        RenameI18nKeyHandler().invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)

        Assertions.assertEquals("export const KEYS = { save: 'common:button.store' } as const;", text("keys.ts"))
        Assertions.assertEquals("import { KEYS } from './keys';\nexport const a = (t) => [t(KEYS.save), t(KEYS.save)];", text("Form.ts"))
        Assertions.assertTrue(text("locales/en/common.json").contains("\"store\": \"Save\""), text("locales/en/common.json"))
    }

    /**
     * The daemon runs the annotators in parallel, and the reference provider searches a constant's
     * calls from there. The search used to spread its files over that same pool: stopping it at the
     * first call, or a cancellation, threw `ComputationAbortedException` out of the nested search.
     */
    @Test
    fun `an exported constant's call is found from a parallel task, among many files naming it`() {
        val constants = (0 until 6).map { "KEY_$it" }
        val keysFile = addFileToProject(
            "keys.ts",
            constants.joinToString("\n") { "export const $it = 'common:button.save';" }
        )
        val imports = "import { ${constants.joinToString()} } from './keys';"
        repeat(12) { index ->
            addFileToProject("Log$index.ts", "$imports\nexport const log$index = () => [${constants.joinToString()}];")
        }
        addFileToProject("Form.ts", "$imports\nexport const a = (t) => [${constants.joinToString { "t($it)" }}];")
        val literals = ReadAction.compute<List<JSLiteralExpression>, RuntimeException> {
            PsiTreeUtil.findChildrenOfType(keysFile, JSLiteralExpression::class.java).toList()
        }

        val callFiles = ConcurrentHashMap<JSLiteralExpression, String>()
        // Under a progress indicator, and each task in its own read action, as the daemon's are: the
        // overload that took the indicator and a read-action flag is gone in 2026.3.
        val completed = ProgressManager.getInstance().runProcess(Computable {
            JobLauncher.getInstance().invokeConcurrentlyUnderContextProgress(literals) { literal ->
                ReadAction.run<RuntimeException> {
                    ConstantKeyResolver.declarationArgument(literal)?.let { callFiles[literal] = it.containingFile.name }
                }
                true
            }
        }, EmptyProgressIndicator())

        Assertions.assertTrue(completed)
        Assertions.assertEquals(literals.associateWith { "Form.ts" }, callFiles.toMap())
    }

    private fun text(path: String): String = ReadAction.compute<String, RuntimeException> {
        PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!.text
    }
}
