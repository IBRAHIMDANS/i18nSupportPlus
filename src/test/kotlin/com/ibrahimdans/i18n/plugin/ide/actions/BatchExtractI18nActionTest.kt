package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.waitForAsyncWork
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions
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

    private fun candidatesOf(code: String): List<Candidate> {
        myFixture.configureByText("batch.js", code.trimIndent())
        return ReadAction.compute<List<Candidate>, RuntimeException> {
            BatchExtractI18nAction().collectCandidates(myFixture.file)
        }
    }

    private fun translationsText(path: String): String = ReadAction.compute<String, RuntimeException> {
        PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!.text
    }

    private fun acceptDefaultValues() = setTestInputDialog(object : TestInputDialog {
        override fun show(message: String): String? = null
        override fun show(message: String, validator: InputValidator?): String? = null
    })

    @Test
    fun aTextAlreadyTranslatedIsProposedItsKey() = myFixture.runWithConfig(config("json")) {
        myFixture.addFileToProject("locales/en/common.json", """{"actions": {"save": "Save"}}""")
        val candidates = candidatesOf(
            """
                export const a = (i18n) => "Save";
                export const b = (i18n) => "Cancel";
            """
        )
        val found = ReadAction.compute<List<Candidate>, RuntimeException> {
            BatchExtractI18nAction().withExistingKeys(candidates, myFixture.file)
        }
        assertEquals(listOf("common:actions.save", "cancel"), found.map { it.proposedKey })
        assertEquals(listOf(listOf("common:actions.save"), emptyList()), found.map { it.existingKeys })
    }

    @Test
    fun anExistingKeyIsReusedWithoutWritingTheTranslations() = myFixture.runWithConfig(config("json")) {
        myFixture.addFileToProject("assets/test.json", """{"actions": {"save": "Save"}}""")
        val candidates = candidatesOf(
            """
                export const a = (i18n) => "Save";
                export const b = (i18n) => "Goodbye";
            """
        )
        val save = candidates[0].copy(existingKeys = listOf("test:actions.save"))
        acceptDefaultValues()
        BatchExtractI18nAction().extract(
            project, myFixture.editor,
            listOf(save to "test:actions.save", candidates[1] to "test:greeting.goodbye")
        )
        waitForAsyncWork()

        myFixture.checkResult(
            """
                export const a = (i18n) => i18n.t('test:actions.save');
                export const b = (i18n) => i18n.t('test:greeting.goodbye');
            """.trimIndent()
        )
        val translations = translationsText("assets/test.json")
        assertEquals(1, Regex("\"Save\"").findAll(translations).count(), translations)
        assertTrue(translations.contains("\"goodbye\": \"Goodbye\""), translations)
    }

    @Test
    fun literalsSharingAKeyCreateItOnce() = myFixture.runWithConfig(config("json")) {
        myFixture.addFileToProject("assets/test.json", "{}")
        val candidates = candidatesOf(
            """
                export const a = (i18n) => "Hello";
                export const b = (i18n) => "Hello";
            """
        )
        Assertions.assertEquals(candidates[0].proposedKey, candidates[1].proposedKey, "The same text proposes the same key")
        acceptDefaultValues()
        BatchExtractI18nAction().extract(project, myFixture.editor, candidates.map { it to "test:greeting.hello" })
        waitForAsyncWork()

        myFixture.checkResult(
            """
                export const a = (i18n) => i18n.t('test:greeting.hello');
                export const b = (i18n) => i18n.t('test:greeting.hello');
            """.trimIndent()
        )
        val translations = translationsText("assets/test.json")
        assertEquals(1, Regex("\"hello\"").findAll(translations).count(), translations)
    }
}
