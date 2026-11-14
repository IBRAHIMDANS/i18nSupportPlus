package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.refactoring.rename.RenameHandlerRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.ResourceBundle

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

    /**
     * On `KEYS.save`, WebStorm's *Rename in a dialog* is available too: the platform asks which
     * rename to run, naming each by its title (in sentence case). Without one, ours showed its
     * `toString()`.
     */
    @Test
    fun `the choice WebStorm shows names this rename, not its class`() {
        val title = RenameHandlerRegistry.getHandlerTitle(RenameI18nKeyHandler())

        Assertions.assertTrue(title.equals(PluginBundle.message("action.rename.chooser.title"), ignoreCase = true), title)
    }

    /**
     * The platform sorts that choice by title, in sentence case, and preselects the first. In every
     * language the plugin ships, this rename comes before WebStorm's *Rename in a dialog*, whether a
     * language pack translates WebStorm's entry or leaves it in English, and before an entry titled
     * by its class name (`JSRenameWrongRefHandler` on Paraglide's `m.hello`).
     */
    @Test
    fun `this rename comes first in the choice in every language the plugin ships`() {
        fun sorted(title: String) = StringUtil.capitalize(StringUtil.toLowerCase(title))
        // A titled entry is shown in sentence case; an untitled one by its toString(), as it is.
        val others = listOf("Rename in a Dialog", "Renommer dans une boîte de dialogue").map(::sorted) +
            "com.intellij.lang.javascript.refactoring.rename.JSRenameWrongRefHandler@2185441c"
        for (locale in listOf(Locale.ROOT, Locale.FRENCH)) {
            val bundle = ResourceBundle.getBundle("messages.I18nBundle", locale)
            val ours = sorted(bundle.getString("action.rename.chooser.title"))
            for (other in others) Assertions.assertTrue(ours < other, "$locale: '$ours' sorts after '$other'")
        }
    }
}
