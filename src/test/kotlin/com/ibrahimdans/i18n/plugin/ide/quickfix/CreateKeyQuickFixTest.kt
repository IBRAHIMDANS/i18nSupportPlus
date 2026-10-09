package com.ibrahimdans.i18n.plugin.ide.quickfix

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.waitForAsyncWork
import com.ibrahimdans.i18n.plugin.key.parser.KeyParserBuilder
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class CreateKeyQuickFixTest : PlatformBaseTest() {

    /**
     * Writing to several files replaces the extracted text once: the replacement used to run
     * after each file, over the call the previous run had inserted.
     */
    @Test
    fun replacesTheCodeOnceWhateverTheNumberOfFilesWritten() {
        addFileToProject("locales/en/common.json", "{}")
        addFileToProject("locales/fr/common.json", "{}")
        myFixture.configureByText("App.js", "const a = 1;")
        var replacements = 0
        val key = KeyParserBuilder.withSeparators(":", ".").build()
            .parse(RawKey(listOf(KeyElement.literal("common:greeting"))))!!

        val previous = TestDialogManager.setTestInputDialog(answering("Hello"))
        try {
            CreateKeyQuickFix(key, AllSourcesSelector(), "Create key", "Hello") { replacements++ }
                .invoke(project, myFixture.editor)
            waitForAsyncWork()
        } finally {
            TestDialogManager.setTestInputDialog(previous)
        }

        Assertions.assertEquals(1, replacements, "the code must be replaced once")
        listOf("en", "fr").forEach { locale ->
            Assertions.assertEquals("""{"greeting":"Hello"}""", compact("locales/$locale/common.json"), locale)
        }
    }

    /** Only the other locales of the chosen file are left to fill, not every other namespace. */
    @Test
    fun remainingSourcesKeepTheNamespaceOfTheSelection() {
        listOf("en", "fr").forEach { locale ->
            addFileToProject("locales/$locale/common.json", "{}")
            addFileToProject("locales/$locale/account.json", "{}")
        }
        val all = allSources()
        val selected = all.filter { it.displayPath.endsWith("en/account.json") }

        val remaining = CreateKeyQuickFix.remainingSources(all, selected)

        Assertions.assertEquals(listOf("fr/account.json"), remaining.map { it.displayPath.takeLast(15) })
    }

    private fun compact(path: String): String =
        FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(path))!!.text.replace(Regex("\\s"), "")

    private fun allSources(): List<LocalizationSource> =
        ReadAction.compute<List<LocalizationSource>, RuntimeException> {
            project.service<LocalizationSourceService>().findAllSources(project)
        }

    private fun answering(value: String) = object : TestInputDialog {
        override fun show(message: String): String = value
        override fun show(message: String, validator: InputValidator?): String = value
    }
}
