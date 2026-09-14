package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.waitForAsyncWork
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Batch extraction replaces each literal only once its key has been created, so every
 * replacement lands after the ones before it have already changed the text. The ranges used
 * to be plain offsets computed up front, and the second literal of a file was overwritten at
 * the position the first one used to end.
 */
class BatchExtractI18nActionTest : ExtractionTestBase() {

    @Test
    fun extractsSeveralLiteralsOfTheSameFile() = myFixture.runWithConfig(config("json")) {
        myFixture.addFileToProject("assets/test.json", "{}")
        myFixture.configureByText(
            "batch.js",
            """
                export const greet = (i18n) => {
                    return "Hello";
                };
                export const leave = (i18n) => {
                    return "Goodbye everyone";
                };
            """.trimIndent()
        )
        val action = BatchExtractI18nAction()
        val candidates = ReadAction.compute<List<Candidate>, RuntimeException> {
            action.collectCandidates(myFixture.file)
        }
        assertEquals(listOf("Hello", "Goodbye everyone"), candidates.map { it.originalText })

        // The translation value dialog of each key: null falls back to the literal's text.
        setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String? = null
            override fun show(message: String, validator: InputValidator?): String? = null
        })
        action.extract(project, myFixture.editor, candidates.zip(listOf("test:greeting.hello", "test:greeting.goodbye")))
        waitForAsyncWork()

        myFixture.checkResult(
            """
                export const greet = (i18n) => {
                    return i18n.t('test:greeting.hello');
                };
                export const leave = (i18n) => {
                    return i18n.t('test:greeting.goodbye');
                };
            """.trimIndent()
        )
        val translations = ReadAction.compute<String, RuntimeException> {
            PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("assets/test.json"))!!.text
        }
        assertTrue(translations.contains("\"hello\": \"Hello\""), translations)
        assertTrue(translations.contains("\"goodbye\": \"Goodbye everyone\""), translations)
    }
}
