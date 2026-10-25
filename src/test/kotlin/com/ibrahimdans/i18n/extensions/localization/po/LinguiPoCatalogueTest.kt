package com.ibrahimdans.i18n.extensions.localization.po

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.RenameI18nKeyHandler
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.quickfix.CreateKeyDialogQuickFix
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A Lingui project's PO catalogues, as `lingui extract` writes them: an explicit id flagged
 * `#. js-lingui-explicit-id`, origin lines `#: src/App.tsx:12` above an entry, a `<Trans>` source
 * text as its own msgid. Read through the GNU GetText plugin, with *GetText mode* on as the setup
 * wizard sets it for Lingui.
 */
class LinguiPoCatalogueTest : PlatformBaseTest() {

    private companion object {
        const val CATALOGUE = "src/locales/en/messages.po"
        const val FRENCH = "src/locales/fr/messages.po"
        const val HEADER = "msgid \"\"\nmsgstr \"\"\n\"X-Generator: @lingui/cli\\n\"\n\"Language: en\\n\"\n"
    }

    private val config = Config(gettext = true, defaultNs = "messages")

    @AfterEach
    fun restoreDialogs() {
        TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
        CreateKeyDialogQuickFix.opener = { project, key, caller -> TranslationDialog(project, key, Mode.CREATE, caller).show() }
    }

    private fun withCatalogue(entries: String, header: String = HEADER, body: () -> Unit) = myFixture.runWithConfig(config) {
        addFileToProject(CATALOGUE, "$header\n$entries")
        body()
    }

    private fun component(vararg calls: String) = """
        import { useLingui } from "@lingui/react";
        export function App() { const { i18n } = useLingui(); return [${calls.joinToString()}]; }
    """.trimIndent()

    private fun catalogue(): String =
        FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(CATALOGUE))!!.text

    /** Stands in for the translation dialog, which a headless container cannot show (see CreateKeyTest). */
    private fun createKey() {
        CreateKeyDialogQuickFix.opener = { project, key, caller ->
            val viewModel = DialogViewModel(project)
            viewModel.sourcesFor(key.allNamespaces(), caller).forEach { viewModel.saveTranslation(it, key, "Value") }
        }
        val hint = "Create i18n key"
        myFixture.launchActionAndWait(myFixture.filterAvailableIntentions(hint).first { it.text == hint })
    }

    // ── Create i18n key ───────────────────────────────────────────────────────

    @Test
    fun anExplicitIdIsCreatedFlagged() = withCatalogue("msgid \"nav.home\"\nmsgstr \"Home\"\n") {
        myFixture.configureByText("App.tsx", component("i18n._(\"checkout.cou<caret>pon\")"))

        createKey()

        assertTrue(catalogue().contains("#. js-lingui-explicit-id\nmsgid \"checkout.coupon\"\nmsgstr \"Value\""), catalogue())
    }

    @Test
    fun aTransSourceTextIsCreatedWithoutTheFlag() = withCatalogue("msgid \"nav.home\"\nmsgstr \"Home\"\n") {
        myFixture.configureByText("App.tsx", "const Trans0 = () => (<Trans>Good<caret>bye</Trans>);")

        createKey()

        val written = catalogue()
        assertTrue(written.contains("msgid \"Goodbye\"\nmsgstr \"Value\""), written)
        assertFalse(written.contains("js-lingui-explicit-id\nmsgid \"Goodbye\""), "a source text is no explicit id: $written")
    }

    @Test
    fun aGettextCatalogueGetsNoFlag() = withCatalogue("msgid \"nav.home\"\nmsgstr \"Home\"\n", header = "msgid \"\"\nmsgstr \"\"\n\"Language: en\\n\"\n") {
        myFixture.configureByText("App.tsx", component("i18n._(\"checkout.cou<caret>pon\")"))

        createKey()

        assertFalse(catalogue().contains("js-lingui-explicit-id"), catalogue())
        assertTrue(catalogue().contains("msgid \"checkout.coupon\""), catalogue())
    }

    // ── Origin lines ──────────────────────────────────────────────────────────

    @Test
    fun anEntryUnderOriginLinesResolvesAsWithout() = withCatalogue(
        "#. js-lingui-explicit-id\n#: src/App.tsx:12\n#: src/Nav.tsx:3\nmsgid \"nav.settings\"\nmsgstr \"Settings\"\n\n" +
            "msgid \"nav.home\"\nmsgstr \"Home\"\n"
    ) {
        myFixture.configureByText(
            "App.tsx",
            component("i18n._(\"nav.settings\")", "i18n._(\"nav.home\")", "i18n._(\"<error descr=\"Unresolved key\">nav.absent</error>\")")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    // ── Completion ────────────────────────────────────────────────────────────

    @Test
    fun completionOffersTheMsgids() = withCatalogue(
        "#: src/App.tsx:12\nmsgid \"nav.settings\"\nmsgstr \"Settings\"\n\nmsgid \"nav.home\"\nmsgstr \"Home\"\n"
    ) {
        myFixture.configureByText("App.tsx", component("i18n._(\"<caret>\")"))
        myFixture.completeBasic()

        val offered = myFixture.lookupElementStrings.orEmpty()
        assertTrue(offered.containsAll(listOf("nav.home", "nav.settings")), "every msgid must be offered: $offered")
        assertFalse("" in offered, "the header is no key: $offered")
    }

    // ── Rename ────────────────────────────────────────────────────────────────

    @Test
    fun renameRewritesTheMsgidInEveryLocaleAndEveryCall() = withCatalogue(
        "#. js-lingui-explicit-id\n#: src/App.tsx:12\nmsgid \"nav.home\"\nmsgstr \"Home\"\n\nmsgid \"nav.settings\"\nmsgstr \"Settings\"\n"
    ) {
        myFixture.addFileToProject(FRENCH, "$HEADER\n#. js-lingui-explicit-id\nmsgid \"nav.home\"\nmsgstr \"Accueil\"\n")
        myFixture.addFileToProject("src/Nav.tsx", component("i18n._(\"nav.home\")"))
        myFixture.configureByText("App.tsx", component("i18n._(\"nav.ho<caret>me\")"))
        TestDialogManager.setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = "nav.start"
            override fun show(message: String, validator: InputValidator?): String = "nav.start"
        })

        RenameI18nKeyHandler().invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)

        assertTrue(myFixture.editor.document.text.contains("i18n._(\"nav.start\")"), myFixture.editor.document.text)
        val written = catalogue()
        assertTrue(written.contains("#. js-lingui-explicit-id\n#: src/App.tsx:12\nmsgid \"nav.start\"\nmsgstr \"Home\""), written)
        assertTrue(written.contains("msgid \"nav.settings\""), "the other entries stay: $written")
        val french = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(FRENCH))!!.text
        assertTrue(french.contains("msgid \"nav.start\"\nmsgstr \"Accueil\""), "every locale's msgid follows: $french")
        val nav = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("src/Nav.tsx"))!!.text
        assertTrue(nav.contains("i18n._(\"nav.start\")"), nav)
    }
}
