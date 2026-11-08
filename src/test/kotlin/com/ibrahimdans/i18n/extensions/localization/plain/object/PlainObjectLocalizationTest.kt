package com.ibrahimdans.i18n.extensions.localization.plain.`object`

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * PO catalogues as [PlainObjectLocalization] reads and writes them, through the GNU GetText plugin's
 * PSI: a msgid is one flat key, dots included, and an entry is written escaped where the catalogue
 * keeps it. Ctrl+click from a msgid goes through [PlainObjectGotoDeclarationHandler], which
 * `plainObjectConfig.xml` registers.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class PlainObjectLocalizationTest : PlatformBaseTest() {

    private companion object {
        const val CATALOGUE = "src/locales/en/messages.po"
        const val HEADER = "msgid \"\"\nmsgstr \"\"\n\"Language: en\\n\"\n"
    }

    /** Runs [block] with [entries] in a PO catalogue when the GNU GetText plugin is loaded, and skips the test otherwise. */
    private fun withCatalogue(entries: String, config: Config = Config(gettext = true, defaultNs = "messages"), block: (PsiFile) -> Unit) {
        val getText = PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.plugins.localization"))
        assumeTrue(getText != null && getText.isEnabled, "the GNU GetText plugin is not loaded in this sandbox")
        myFixture.runWithConfig(config) {
            val file = addFileToProject(CATALOGUE, "$HEADER\n$entries")
            block(file)
        }
    }

    private fun tree(file: PsiFile) = PlainObjectLocalization().elementsTree(file)

    private fun catalogue(): String = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(CATALOGUE))!!.text

    private fun write(file: PsiFile, key: String, value: String) = WriteCommandAction.runWriteCommandAction(project) {
        PlainObjectContentGenerator().generateTranslationEntry(file, key, value)
    }

    // ── Reading ─────────────────────────────────────────────────────────────────

    @Test
    fun aMsgidIsOneFlatKey() = withCatalogue("msgid \"user.name\"\nmsgstr \"Name\"\n") { file ->
        ReadAction.run<RuntimeException> {
            val tree = tree(file)
            Assertions.assertEquals("Name", tree.findChild("user.name")?.value()?.text?.unQuote())
            Assertions.assertNull(tree.findChild("user"), "a msgid is not split on its dots")
            Assertions.assertEquals(listOf("user.name"), tree.entries()?.map { it.first }, "the header is no key")
        }
    }

    @Test
    fun aMsgidWithAnApostropheIsRead() = withCatalogue("msgid \"it's.done\"\nmsgstr \"Done\"\n") { file ->
        ReadAction.run<RuntimeException> {
            Assertions.assertEquals("Done", tree(file).findChild("it's.done")?.value()?.text?.unQuote())
        }
    }

    // ── Writing ─────────────────────────────────────────────────────────────────

    @Test
    fun anEntryIsAppendedAfterABlankLine() = withCatalogue("msgid \"user.name\"\nmsgstr \"Name\"\n") { file ->
        write(file, "user.email", "E-mail")
        Assertions.assertTrue(
            catalogue().endsWith("msgid \"user.name\"\nmsgstr \"Name\"\n\nmsgid \"user.email\"\nmsgstr \"E-mail\"\n"),
            catalogue()
        )
        ReadAction.run<RuntimeException> {
            Assertions.assertEquals("E-mail", tree(file).findChild("user.email")?.value()?.text?.unQuote())
        }
    }

    @Test
    fun aSortedCatalogueKeepsItsHeaderFirst() = withCatalogue(
        "msgid \"user.name\"\nmsgstr \"Name\"\n",
        Config(gettext = true, defaultNs = "messages", extractSorted = true)
    ) { file ->
        write(file, "app.title", "App")
        val text = catalogue()
        Assertions.assertTrue(text.startsWith(HEADER), text)
        Assertions.assertTrue(text.indexOf("msgid \"app.title\"") < text.indexOf("msgid \"user.name\""), text)
    }

    @Test
    fun aKeyWithQuotesIsWrittenEscaped() = withCatalogue("msgid \"user.name\"\nmsgstr \"Name\"\n") { file ->
        write(file, "say \"hi\"", "Hello \"you\"")
        Assertions.assertTrue("msgid \"say \\\"hi\\\"\"\nmsgstr \"Hello \\\"you\\\"\"\n" in catalogue(), catalogue())
    }

    // ── Navigation ──────────────────────────────────────────────────────────────

    @Test
    fun ctrlClickOnAMsgidUnderOriginLinesReachesTheCall() = withCatalogue("#: src/App.tsx:3\nmsgid \"nav.home\"\nmsgstr \"Home\"\n") {
        myFixture.addFileToProject(
            "src/App.tsx",
            "import { useLingui } from \"@lingui/react\";\nexport function App() { const { i18n } = useLingui(); return i18n._(\"nav.home\"); }\n"
        )
        val text = catalogue()
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(CATALOGUE))
        myFixture.editor.caretModel.moveToOffset(text.indexOf("nav.home") + 2)
        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset)
        Assertions.assertEquals(listOf("nav.home"), targets.map { it.text.unQuote() })
    }
}
