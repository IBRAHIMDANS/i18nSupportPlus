package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.translate.Translation
import com.ibrahimdans.i18n.plugin.translate.TranslationProvider
import com.ibrahimdans.i18n.plugin.translate.TranslationRequest
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Disposer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * *Translate Empty Locales* in the translation dialog: the donor value goes to the project's engines
 * for each empty locale, and only empty fields are filled — nothing is written before *OK*.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class TranslationDialogMachineTranslationTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")
    private val original = TranslationDialog.machineTranslator

    /** The requests the scripted engine received. */
    private val requests = mutableListOf<TranslationRequest>()

    private fun engine(answer: (TranslationRequest) -> Translation) = object : TranslationProvider {
        override fun translate(request: TranslationRequest, indicator: ProgressIndicator?): List<Translation> {
            requests += request
            return listOf(answer(request))
        }
    }

    @AfterEach
    fun restore() {
        TranslationDialog.machineTranslator = original
    }

    private fun seed() {
        addFileToProject("locales/en/common.json", """{"greeting": "Hello {{name}}"}""")
        addFileToProject("locales/fr/common.json", """{"greeting": ""}""")
        addFileToProject("locales/de/common.json", """{"greeting": "Hallo {{name}}"}""")
        addFileToProject("locales/es/common.json", """{}""")
    }

    private fun withDialog(block: (TranslationDialog) -> Unit) {
        val dialog = TranslationDialog(project, KeysSynchronizer().buildFullKey("common:greeting", config), Mode.EDIT)
        try {
            block(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    @Test
    fun `the button is hidden until the project opts in`() = myFixture.runWithConfig(config) {
        seed()
        TranslationDialog.machineTranslator = { null }
        withDialog { Assertions.assertFalse(it.translateVisible) }

        TranslationDialog.machineTranslator = { engine { Translation.Done("x") } }
        withDialog { Assertions.assertTrue(it.translateVisible) }
    }

    @Test
    fun `only the empty locales are filled, from the donor value`() = myFixture.runWithConfig(config) {
        seed()
        TranslationDialog.machineTranslator = { engine { Translation.Done("[${it.target}] ${it.texts.single()}") } }

        withDialog { dialog ->
            dialog.translateEmptyLocales()

            Assertions.assertEquals("[fr] Hello {{name}}", dialog.valueOf("fr"))
            Assertions.assertEquals("[es] Hello {{name}}", dialog.valueOf("es"))
            Assertions.assertEquals("Hallo {{name}}", dialog.valueOf("de"), "a value already there is never replaced")
            Assertions.assertEquals("Hello {{name}}", dialog.valueOf("en"))
        }
        Assertions.assertEquals(setOf("fr", "es"), requests.map { it.target }.toSet())
        Assertions.assertTrue(requests.all { it.source == "en" && "common:greeting" in it.context }, "$requests")
        Assertions.assertEquals("", myFixture.findFileInTempDir("locales/fr/common.json").let { String(it.contentsToByteArray()) }
            .substringAfter("\"greeting\": \"").substringBefore('"'), "nothing written before OK")
    }

    @Test
    fun `a failed locale stays empty and says why`() = myFixture.runWithConfig(config) {
        seed()
        TranslationDialog.machineTranslator = {
            engine { if (it.target == "fr") Translation.Failed("quota") else Translation.Done("Hola {{name}}") }
        }

        withDialog { dialog ->
            dialog.translateEmptyLocales()

            Assertions.assertEquals("", dialog.valueOf("fr"))
            Assertions.assertEquals("Hola {{name}}", dialog.valueOf("es"))
            Assertions.assertTrue("fr: quota" in dialog.translateMessage, dialog.translateMessage)
        }
    }

    @Test
    fun `nothing is sent without a donor value`() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"greeting": ""}""")
        addFileToProject("locales/fr/common.json", """{"greeting": ""}""")
        TranslationDialog.machineTranslator = { engine { Translation.Done("x") } }

        withDialog { dialog ->
            dialog.translateEmptyLocales()
            Assertions.assertEquals("", dialog.valueOf("fr"))
        }
        Assertions.assertTrue(requests.isEmpty())
    }
}
