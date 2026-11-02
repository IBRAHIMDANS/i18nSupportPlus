package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.RenameI18nKeyHandler
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nReference
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * `t(['specific', 'generic'])`: i18next shows the first key that exists. Since #752 the annotator reads
 * the array; each quoted element now carries the key's reference too — Ctrl+click, Find Usages, Shift+F6.
 */
class KeyArrayReferencesTest : PlatformBaseTest() {

    private fun translations() {
        addFileToProject("locales/en/common.json", """{"errors": {"timeout": "Timeout", "unknown": "Unknown error"}}""")
        addFileToProject("locales/fr/common.json", """{"errors": {"timeout": "Délai dépassé", "unknown": "Erreur inconnue"}}""")
    }

    private fun literal(text: String): JSLiteralExpression =
        PsiTreeUtil.findChildrenOfType(myFixture.file, JSLiteralExpression::class.java).single { it.text == text }

    private fun keyReferenceOf(text: String): I18nReference? = ReadAction.compute<I18nReference?, RuntimeException> {
        literal(text).references.filterIsInstance<I18nReference>().firstOrNull()
    }

    @Test
    fun `each element of a key array resolves to its translation in every locale`() {
        translations()
        myFixture.configureByText(
            "Error.js",
            "export const message = (t) => t(['common:errors.timeout', 'common:errors.unknown']);"
        )

        for (key in listOf("'common:errors.timeout'", "'common:errors.unknown'")) {
            val reference = keyReferenceOf(key)
            Assertions.assertNotNull(reference, "$key carries the key's reference")
            val targets = ReadAction.compute<List<String>, RuntimeException> {
                reference!!.multiResolve(false).mapNotNull { it.element?.containingFile?.virtualFile?.path }
            }
            Assertions.assertEquals(2, targets.size, "$key resolves in both locales: $targets")
        }
    }

    @Test
    fun `an element under a useTranslation namespace resolves in that namespace`() {
        translations()
        myFixture.configureByText(
            "Error.tsx",
            "import { useTranslation } from 'react-i18next';\n" +
                "export const E = () => { const { t } = useTranslation('common'); return t(['errors.timeout', 'errors.unknown']); };"
        )

        val reference = keyReferenceOf("'errors.unknown'")
        Assertions.assertNotNull(reference)
        Assertions.assertTrue(ReadAction.compute<Boolean, RuntimeException> { reference!!.multiResolve(false).isNotEmpty() })
    }

    @Test
    fun `a template element is a key as when passed directly, an array passed second and an unconfigured call are not`() {
        translations()
        myFixture.configureByText(
            "Other.js",
            "export const a = (t, code, toast) => [\n" +
                "  t([`common:errors.\${code}`, 'common:errors.unknown']),\n" +
                "  t('common:errors.timeout', ['common:errors.timeout']),\n" +
                "  toast.t(['common:errors.timeout']),\n" +
                "];"
        )

        val keyed = ReadAction.compute<List<String>, RuntimeException> {
            PsiTreeUtil.findChildrenOfAnyType(myFixture.file, JSLiteralExpression::class.java, JSStringTemplateExpression::class.java)
                .filter { it.references.any { reference -> reference is I18nReference } }
                .map { it.text }
        }
        Assertions.assertEquals(listOf("`common:errors.\${code}`", "'common:errors.unknown'", "'common:errors.timeout'"), keyed)
    }

    @Test
    fun `a rename from an element rewrites it and the translation, not the other element`() {
        translations()
        myFixture.configureByText(
            "Error.js",
            "export const message = (t) => t(['common:errors.timeout', 'common:errors.unkn<caret>own']);"
        )
        setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = "generic"
            override fun show(message: String, validator: InputValidator?): String = "generic"
        })

        RenameI18nKeyHandler().invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)

        Assertions.assertEquals(
            "export const message = (t) => t(['common:errors.timeout', 'common:errors.generic']);",
            myFixture.file.text
        )
        Assertions.assertTrue(text("locales/en/common.json").contains("\"generic\": \"Unknown error\""), text("locales/en/common.json"))
        Assertions.assertTrue(text("locales/fr/common.json").contains("\"generic\": \"Erreur inconnue\""), text("locales/fr/common.json"))
    }

    private fun text(path: String): String = ReadAction.compute<String, RuntimeException> {
        PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!.text
    }
}
