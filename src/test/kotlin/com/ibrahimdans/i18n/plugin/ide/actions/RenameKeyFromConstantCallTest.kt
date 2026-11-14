package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Shift+F6 on the constant of `t(KEYS.save)` renames the key the constant holds, as from its
 * literal. It used to rename the JavaScript property: valid code, key left as it was.
 */
class RenameKeyFromConstantCallTest : PlatformBaseTest() {

    @AfterEach
    fun resetDialogs() {
        TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
    }

    private fun text(path: String): String = ReadAction.compute<String, RuntimeException> {
        PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!.text
    }

    private fun available(): Boolean {
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, myFixture.file)
            .build()
        return ReadAction.compute<Boolean, RuntimeException> { RenameI18nKeyHandler().isAvailableOnDataContext(context) }
    }

    private fun rename(newName: String) {
        TestDialogManager.setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = newName
            override fun show(message: String, validator: InputValidator?): String = newName
        })
        RenameI18nKeyHandler().invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)
    }

    @Test
    fun `an imported constant's key is renamed from the call, the call left as written`() {
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save"}}""")
        addFileToProject("keys.ts", "export const KEYS = { save: 'common:button.save' } as const;")
        myFixture.configureByText("Form.ts", "import { KEYS } from './keys';\nexport const a = (t) => t(KEYS.sa<caret>ve);")

        Assertions.assertTrue(available())
        rename("store")

        Assertions.assertEquals("export const KEYS = { save: 'common:button.store' } as const;", text("keys.ts"))
        Assertions.assertEquals("import { KEYS } from './keys';\nexport const a = (t) => t(KEYS.save);", myFixture.editor.document.text)
        Assertions.assertTrue(text("locales/en/common.json").contains("\"store\": \"Save\""), text("locales/en/common.json"))
    }

    /** The literal sits before the caret in the same file: the caret is never read as a position in it. */
    @Test
    fun `a local constant's key is renamed as a whole key, not as one of its levels`() {
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save"}}""")
        myFixture.configureByText(
            "Local.ts",
            "const SAVE_KEY = 'common:button.save';\nexport const a = (t) => t(SAVE_<caret>KEY);"
        )

        rename("store")

        Assertions.assertEquals(
            "const SAVE_KEY = 'common:button.store';\nexport const a = (t) => t(SAVE_KEY);",
            myFixture.editor.document.text
        )
        Assertions.assertTrue(text("locales/en/common.json").contains("\"store\": \"Save\""), text("locales/en/common.json"))
    }

    @Test
    fun `outside a translation call, the platform's rename keeps the constant`() {
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save"}}""")
        addFileToProject("keys.ts", "export const KEYS = { save: 'common:button.save' } as const;")
        myFixture.configureByText(
            "Log.ts",
            "import { KEYS } from './keys';\nexport const a = (t) => [t(KEYS.save), console.log(KEYS.sa<caret>ve)];"
        )

        Assertions.assertFalse(available())
    }

    @Test
    fun `on the qualifier, the platform's rename keeps the object`() {
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save"}}""")
        addFileToProject("keys.ts", "export const KEYS = { save: 'common:button.save' } as const;")
        myFixture.configureByText("Form.ts", "import { KEYS } from './keys';\nexport const a = (t) => t(KE<caret>YS.save);")

        Assertions.assertFalse(available())
    }

    @Test
    fun `a constant holding two keys is left to the platform's rename`() {
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save", "cancel": "Cancel"}}""")
        myFixture.configureByText(
            "Both.ts",
            "declare const ok: boolean;\nconst KEY = ok ? 'common:button.save' : 'common:button.cancel';\nexport const a = (t) => t(K<caret>EY);"
        )

        Assertions.assertFalse(available())
    }
}
