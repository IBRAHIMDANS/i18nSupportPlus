package com.ibrahimdans.i18n.plugin.ide

import com.ibrahimdans.i18n.extensions.lang.js.JsFoldingBuilder
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.RenameI18nKeyHandler
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.quickfix.CreateKeyDialogQuickFix
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * next-intl's `useTranslations('Nav')` makes `t('title')` stand for `Nav.title`. Resolution,
 * completion and the `t` methods were covered; rename, folding and Create key on a plain `t`
 * were not, under a namespace or without one (`useTranslations()`, the key written in full).
 */
class NextIntlNamespaceActionsTest : PlatformBaseTest() {

    private companion object {
        const val MESSAGES_PATH = "messages/en.json"
        const val MESSAGES = """{"Nav": {"title": "Home"}, "zzOther": {"title": "Other"}}"""
    }

    private val config = Config(foldingPreferredLanguage = "en", foldingMaxLength = 30, foldingEnabled = true)

    @AfterEach
    fun restoreDialogs() {
        TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
        CreateKeyDialogQuickFix.opener = { project, key, caller -> TranslationDialog(project, key, Mode.CREATE, caller).show() }
    }

    private fun withMessages(body: () -> Unit) = myFixture.runWithConfig(config) {
        addFileToProject(MESSAGES_PATH, MESSAGES)
        body()
    }

    private fun component(namespace: String?, call: String) = """
        import { useTranslations } from 'next-intl';
        export default function Nav() {
            const t = useTranslations(${namespace?.let { "'$it'" }.orEmpty()});
            return $call;
        }
    """.trimIndent()

    private fun messages(): String =
        FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(MESSAGES_PATH))!!.text.replace(Regex("\\s"), "")

    private fun foldPlaceholders(): List<String> = ReadAction.compute<List<String>, RuntimeException> {
        JsFoldingBuilder().buildFoldRegions(myFixture.file, myFixture.editor.document, false).map { it.placeholderText.orEmpty() }
    }

    private fun rename(to: String) {
        TestDialogManager.setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = to
            override fun show(message: String, validator: InputValidator?): String = to
        })
        RenameI18nKeyHandler().invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)
    }

    /** Stands in for the translation dialog, which a headless container cannot show (see CreateKeyTest). */
    private fun createKey() {
        CreateKeyDialogQuickFix.opener = { project, key, caller ->
            val viewModel = DialogViewModel(project)
            viewModel.sourcesFor(key.allNamespaces(), caller).forEach { viewModel.saveTranslation(it, key, "Value") }
        }
        val hint = "Create i18n key"
        myFixture.launchActionAndWait(myFixture.filterAvailableIntentions(hint).first { it.text == hint })
    }

    // ── Rename ────────────────────────────────────────────────────────────────

    @Test
    fun renameUnderANamespaceKeepsTheNamespaceOutOfTheCall() = withMessages {
        myFixture.configureByText("Nav.tsx", component("Nav", "t('ti<caret>tle')"))

        rename("heading")

        assertTrue(myFixture.editor.document.text.contains("t('heading')"), myFixture.editor.document.text)
        assertEquals("""{"Nav":{"heading":"Home"},"zzOther":{"title":"Other"}}""", messages())
    }

    /** The dialog asks for the last segment, as everywhere: the rest of the written key is kept. */
    @Test
    fun renameWithoutANamespaceKeepsTheWrittenPath() = withMessages {
        myFixture.configureByText("Nav.tsx", component(null, "t('Nav.ti<caret>tle')"))

        rename("heading")

        assertTrue(myFixture.editor.document.text.contains("t('Nav.heading')"), myFixture.editor.document.text)
        assertEquals("""{"Nav":{"heading":"Home"},"zzOther":{"title":"Other"}}""", messages())
    }

    // ── Folding ───────────────────────────────────────────────────────────────

    @Test
    fun foldingUnderANamespaceShowsTheNamespaceValue() = withMessages {
        myFixture.configureByText("Nav.tsx", component("Nav", "t('title')"))
        assertEquals(listOf("Home"), foldPlaceholders())
    }

    @Test
    fun foldingWithoutANamespaceShowsTheFullKeyValue() = withMessages {
        myFixture.configureByText("Nav.tsx", component(null, "t('Nav.title')"))
        assertEquals(listOf("Home"), foldPlaceholders())
    }

    // ── Create key ────────────────────────────────────────────────────────────

    @Test
    fun createKeyUnderANamespaceWritesItInTheNamespace() = withMessages {
        myFixture.configureByText("Nav.tsx", component("Nav", "t('mis<caret>sing')"))

        createKey()

        val written = messages()
        assertTrue(written.contains(""""Nav":{"title":"Home","missing":"Value"}"""), written)
        assertFalse(written.startsWith("""{"missing""""), "the key must not land at the root: $written")
    }

    @Test
    fun createKeyWithoutANamespaceWritesTheFullKey() = withMessages {
        myFixture.configureByText("Nav.tsx", component(null, "t('Nav.mis<caret>sing')"))

        createKey()

        val written = messages()
        assertTrue(written.contains(""""Nav":{"title":"Home","missing":"Value"}"""), written)
    }
}
