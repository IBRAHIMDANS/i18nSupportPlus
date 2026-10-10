package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Shift+F6 on an intermediate level of a key — `button` in `common:button.save` — moves every key
 * under it (`button.*` → `actions.*`): every locale, every call site however it is written, a hook's
 * `keyPrefix`; nothing that resolves elsewhere. A level that already exists is refused, and keys
 * built at runtime are listed, not rewritten.
 */
class RenameKeyLevelTest : PlatformBaseTest() {

    /** The messages the confirmation and error dialogs showed. */
    private val shown = mutableListOf<String>()

    @AfterEach
    fun resetDialogs() {
        TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
    }

    private fun text(path: String): String = ReadAction.compute<String, RuntimeException> {
        PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!.text
    }

    private fun renameAtCaret(newName: String, answer: Int = Messages.YES) {
        TestDialogManager.setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = newName
            override fun show(message: String, validator: InputValidator?): String = newName
        })
        TestDialogManager.setTestDialog { message -> shown += message; answer }
        RenameI18nKeyHandler().invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)
    }

    private fun addProject() {
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save", "cancel": "Cancel"}, "form": {"button": {"save": "Submit"}}}""")
        addFileToProject("locales/fr/common.json", """{"button": {"save": "Enregistrer", "cancel": "Annuler"}}""")
        addFileToProject("locales/en/other.json", """{"button": {"save": "Other"}}""")
        addFileToProject("src/Explicit.js", "export const b = (t) => t('common:button.cancel');")
        addFileToProject(
            "src/Hook.tsx",
            "import { useTranslation } from 'react-i18next';\n" +
                "export const H = () => { const { t } = useTranslation('common'); return t('button.save'); };"
        )
        addFileToProject(
            "src/Prefix.tsx",
            "import { useTranslation } from 'react-i18next';\n" +
                "export const P = () => { const { t } = useTranslation('common', { keyPrefix: 'button' }); return t('save'); };"
        )
        addFileToProject("src/Unrelated.js", "export const u = (t) => [t('other:button.save'), t('common:form.button.save')];")
        addFileToProject("src/Dynamic.js", "export const d = (t, action) => t(`common:button.\${action}`);")
    }

    @Test
    fun fromTheCodeTheLevelMovesEverywhereItIsWritten() {
        addProject()
        myFixture.configureByText("First.js", "export const a = (t) => t('common:but<caret>ton.save');")

        renameAtCaret("actions")

        assertEquals("export const a = (t) => t('common:actions.save');", myFixture.editor.document.text)
        assertTrue(text("locales/en/common.json").contains("\"actions\": {\"save\": \"Save\", \"cancel\": \"Cancel\"}"), text("locales/en/common.json"))
        assertTrue(text("locales/fr/common.json").contains("\"actions\": {"), text("locales/fr/common.json"))
        assertTrue(text("src/Explicit.js").contains("t('common:actions.cancel')"), text("src/Explicit.js"))
        assertTrue(text("src/Hook.tsx").contains("t('actions.save')"), text("src/Hook.tsx"))
        assertTrue(text("src/Prefix.tsx").contains("keyPrefix: 'actions'"), text("src/Prefix.tsx"))
        assertTrue(text("src/Prefix.tsx").contains("t('save')"), "a key under the prefix is left as written: ${text("src/Prefix.tsx")}")
    }

    @Test
    fun whatResolvesElsewhereIsLeftAlone() {
        addProject()
        myFixture.configureByText("First.js", "export const a = (t) => t('common:but<caret>ton.save');")

        renameAtCaret("actions")

        assertTrue(text("locales/en/other.json").contains("\"button\""), text("locales/en/other.json"))
        assertTrue(text("locales/en/common.json").contains("\"form\": {\"button\": {\"save\": \"Submit\"}}"), text("locales/en/common.json"))
        assertTrue(text("src/Unrelated.js").contains("t('other:button.save')"), text("src/Unrelated.js"))
        assertTrue(text("src/Unrelated.js").contains("t('common:form.button.save')"), text("src/Unrelated.js"))
    }

    @Test
    fun keysBuiltAtRuntimeAreListedNotRewritten() {
        addProject()
        myFixture.configureByText("First.js", "export const a = (t) => t('common:but<caret>ton.save');")

        renameAtCaret("actions")

        assertTrue(shown.single().contains("Dynamic.js:1"), shown.single())
        assertTrue(text("src/Dynamic.js").contains("common:button.\${action}"), text("src/Dynamic.js"))
    }

    @Test
    fun fromATranslationFileTheLevelUnderTheCaretIsRenamed() {
        addProject()
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("locales/fr/common.json"))
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("button") + 2)

        renameAtCaret("actions")

        assertTrue(text("locales/en/common.json").contains("\"actions\": {"), text("locales/en/common.json"))
        assertTrue(text("locales/fr/common.json").contains("\"actions\": {"), text("locales/fr/common.json"))
        assertTrue(text("src/Explicit.js").contains("t('common:actions.cancel')"), text("src/Explicit.js"))
    }

    @Test
    fun anExistingLevelIsRefusedNotMerged() {
        addProject()
        addFileToProject("locales/de/common.json", """{"button": {"save": "Speichern"}, "actions": {"edit": "Bearbeiten"}}""")
        myFixture.configureByText("First.js", "export const a = (t) => t('common:but<caret>ton.save');")
        val before = text("locales/en/common.json")

        renameAtCaret("actions")

        assertTrue(shown.single().contains("already exists"), shown.single())
        assertEquals(before, text("locales/en/common.json"))
        assertTrue(text("src/Explicit.js").contains("t('common:button.cancel')"))
    }

    @Test
    fun aCancelledConfirmationChangesNothing() {
        addProject()
        myFixture.configureByText("First.js", "export const a = (t) => t('common:but<caret>ton.save');")
        val before = text("locales/en/common.json")

        renameAtCaret("actions", answer = Messages.NO)

        assertEquals(before, text("locales/en/common.json"))
        assertEquals("export const a = (t) => t('common:button.save');", myFixture.editor.document.text)
    }

    /** On the last segment, Shift+F6 still renames the key alone. */
    @Test
    fun theLastSegmentIsStillAPlainRename() {
        addProject()
        myFixture.configureByText("First.js", "export const a = (t) => t('common:button.sa<caret>ve');")

        renameAtCaret("store")

        assertTrue(text("locales/en/common.json").contains("\"button\": {\"store\": \"Save\""), text("locales/en/common.json"))
        assertFalse(text("locales/en/common.json").contains("\"actions\""))
    }
}
