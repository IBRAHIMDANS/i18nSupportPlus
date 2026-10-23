package com.ibrahimdans.i18n.plugin.ide.references.code

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.RenameI18nKeyHandler
import com.ibrahimdans.i18n.plugin.ide.references.translation.TranslationToCodeReference
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * react-intl's ids carry a reference: `<FormattedMessage id>`, `formatMessage({ id })` and the
 * descriptors of `defineMessages` / `defineMessage`. They used to be annotated only — no Ctrl+click
 * to the translation, no usage found from the translation file, no Shift+F6.
 */
class ReactIntlReferenceTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")

    private fun withTranslations(test: () -> Unit) = myFixture.runWithConfig(config) {
        addFileToProject("assets/translation.json", """{"greeting": {"hello": "Bonjour"}}""")
        test()
    }

    /** The I18nReference on the element at the caret — its parent, as a literal holds it — or null. */
    private fun referenceAtCaret(): I18nReference? = ReadAction.compute<I18nReference?, RuntimeException> {
        val leaf = myFixture.file.findElementAt(myFixture.caretOffset)
        listOfNotNull(leaf?.parent, leaf).firstNotNullOfOrNull { it.references.filterIsInstance<I18nReference>().firstOrNull() }
    }

    private fun assertResolvesToTranslation() {
        val reference = referenceAtCaret()
        assertNotNull(reference, "no reference at the caret of ${myFixture.file.text}")
        assertEquals("Bonjour", ReadAction.compute<String?, RuntimeException> { reference!!.resolve()?.text?.unQuote() })
    }

    @Test
    fun formattedMessageIdLeadsToTheTranslation() = withTranslations {
        myFixture.configureByText("Page.tsx", "const C = () => (<FormattedMessage id=\"greeting.hel<caret>lo\" defaultMessage=\"Hello\" />);")
        assertResolvesToTranslation()
    }

    @Test
    fun qualifiedFormatMessageIdLeadsToTheTranslation() = withTranslations {
        myFixture.configureByText("Page.tsx", "const f = (intl) => intl.formatMessage({ id: 'greeting.hel<caret>lo', defaultMessage: 'Hello' });")
        assertResolvesToTranslation()
    }

    @Test
    fun destructuredFormatMessageIdLeadsToTheTranslation() = withTranslations {
        myFixture.configureByText(
            "Page.tsx",
            "const f = () => { const { formatMessage } = useIntl(); return formatMessage({ id: 'greeting.hel<caret>lo' }); };"
        )
        assertResolvesToTranslation()
    }

    @Test
    fun defineMessagesEntryLeadsToTheTranslation() = withTranslations {
        myFixture.configureByText(
            "messages.ts",
            "export const messages = defineMessages({ greeting: { id: 'greeting.hel<caret>lo', defaultMessage: 'Hello' } });"
        )
        assertResolvesToTranslation()
    }

    @Test
    fun defineMessageIdLeadsToTheTranslation() = withTranslations {
        myFixture.configureByText("messages.ts", "export const greeting = defineMessage({ id: 'greeting.hel<caret>lo' });")
        assertResolvesToTranslation()
    }

    /** `id` names an element on any other tag, and `defaultMessage` is source text: neither is a key. */
    @Test
    fun noReferenceOnAnUnrelatedId() = withTranslations {
        myFixture.configureByText("Form.tsx", "const C = () => (<input id=\"greeting.hel<caret>lo\" />);")
        assertEquals(null, referenceAtCaret())
        myFixture.configureByText("Other.ts", "const o = register({ id: 'greeting.hel<caret>lo' });")
        assertEquals(null, referenceAtCaret())
        myFixture.configureByText("Default.tsx", "const C = () => (<FormattedMessage id=\"greeting.hello\" defaultMessage=\"greeting.hel<caret>lo\" />);")
        assertEquals(null, referenceAtCaret())
    }

    @Test
    fun theTranslationLeadsToEveryForm() = withTranslations {
        addFileToProject("src/Page.tsx", "const C = () => (<FormattedMessage id=\"greeting.hello\" />);")
        addFileToProject("src/hook.ts", "const f = (intl) => intl.formatMessage({ id: 'greeting.hello' });")
        addFileToProject("src/messages.ts", "export const messages = defineMessages({ greeting: { id: 'greeting.hello' } });")
        addFileToProject("src/Form.tsx", "const C = () => (<input id=\"greeting.hello\" />);")
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("assets/translation.json"))
        val callSites = ReadAction.compute<Set<String?>, RuntimeException> {
            val name = myFixture.file.findElementAt(myFixture.editor.document.text.indexOf("\"hello\"") + 1)?.parent
            val reference = name?.references?.filterIsInstance<TranslationToCodeReference>()?.firstOrNull()
            assertNotNull(reference, "no reference on 'hello' of the translation file")
            reference!!.multiResolve(false).map { it.element?.containingFile?.name }.toSet()
        }
        assertEquals(setOf("Page.tsx", "hook.ts", "messages.ts"), callSites)
    }

    @Test
    fun renameRewritesTheIdOfEveryForm() = withTranslations {
        addFileToProject("src/hook.ts", "const f = (intl) => intl.formatMessage({ id: 'greeting.hello' });")
        addFileToProject("src/messages.ts", "export const messages = defineMessages({ greeting: { id: 'greeting.hello' } });")
        addFileToProject("src/Form.tsx", "const C = () => (<input id=\"greeting.hello\" />);")
        myFixture.configureByText("Page.tsx", "const C = () => (<FormattedMessage id=\"greeting.hel<caret>lo\" />);")
        setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = "hi"
            override fun show(message: String, validator: InputValidator?): String = "hi"
        })

        RenameI18nKeyHandler().invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)

        assertEquals("const C = () => (<FormattedMessage id=\"greeting.hi\" />);", myFixture.editor.document.text)
        assertTrue(text("src/hook.ts").contains("id: 'greeting.hi'"), text("src/hook.ts"))
        assertTrue(text("src/messages.ts").contains("id: 'greeting.hi'"), text("src/messages.ts"))
        assertTrue(text("src/Form.tsx").contains("id=\"greeting.hello\""), "an unrelated id must stay: ${text("src/Form.tsx")}")
        assertTrue(text("assets/translation.json").contains("\"hi\": \"Bonjour\""), text("assets/translation.json"))
    }

    private fun text(path: String): String = ReadAction.compute<String, RuntimeException> {
        PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!.text
    }
}
