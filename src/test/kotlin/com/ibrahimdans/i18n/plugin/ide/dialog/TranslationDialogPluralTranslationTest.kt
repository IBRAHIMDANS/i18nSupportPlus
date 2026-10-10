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
 * *Translate Empty Locales* on a plural form: each language gets the forms it has, and a form no
 * locale holds yet is translated from `other`, flagged for review.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class TranslationDialogPluralTranslationTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")
    private val original = TranslationDialog.machineTranslator

    /** The texts the scripted engine was asked to translate, by target locale. */
    private val asked = mutableMapOf<String, String>()

    private val engine = object : TranslationProvider {
        override fun translate(request: TranslationRequest, indicator: ProgressIndicator?): List<Translation> {
            asked[request.target] = request.texts.single()
            return listOf(Translation.Done("[${request.target}] ${request.texts.single()}"))
        }
    }

    @AfterEach
    fun restore() {
        TranslationDialog.machineTranslator = original
    }

    private fun seed() {
        addFileToProject("locales/en/common.json", """{"file_one": "{{count}} file", "file_other": "{{count}} files"}""")
        addFileToProject("locales/ru/common.json", """{"file_one": ""}""")
        addFileToProject("locales/ja/common.json", """{}""")
    }

    private fun withDialog(key: String, block: (TranslationDialog) -> Unit) {
        TranslationDialog.machineTranslator = { engine }
        val dialog = TranslationDialog(project, KeysSynchronizer().buildFullKey(key, config), Mode.EDIT)
        try {
            block(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    @Test
    fun `a form no locale holds is translated from other where the language has it`() = myFixture.runWithConfig(config) {
        seed()
        withDialog("common:file_few") { dialog ->
            dialog.translateEmptyLocales()

            Assertions.assertEquals("[ru] {{count}} files", dialog.valueOf("ru"), "Russian has a few form: from other")
            Assertions.assertEquals("", dialog.valueOf("ja"), "Japanese has no few form")
            Assertions.assertEquals("", dialog.valueOf("en"), "English has no few form")
            Assertions.assertEquals(setOf("ru"), asked.keys)
            Assertions.assertTrue(dialog.translateMessage.contains("ru: translated from the \"other\" form"), dialog.translateMessage)
            Assertions.assertTrue(dialog.translateMessage.contains("ja: the language has no \"few\" plural form"), dialog.translateMessage)
        }
    }

    @Test
    fun `a form the reference holds is translated as is, and skipped where the language lacks it`() = myFixture.runWithConfig(config) {
        seed()
        withDialog("common:file_one") { dialog ->
            dialog.translateEmptyLocales()

            Assertions.assertEquals("[ru] {{count}} file", dialog.valueOf("ru"))
            Assertions.assertEquals("", dialog.valueOf("ja"), "Japanese has no one form")
            Assertions.assertEquals(setOf("ru"), asked.keys)
            Assertions.assertFalse(dialog.translateMessage.contains("Review"), "the form came from its own category")
        }
    }

    @Test
    fun `a key merely named like a category is not a plural form`() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"steps": {"one": "Choose a plan"}}""")
        addFileToProject("locales/ja/common.json", """{}""")
        withDialog("common:steps.one") { dialog ->
            dialog.translateEmptyLocales()

            Assertions.assertEquals("[ja] Choose a plan", dialog.valueOf("ja"), "no steps.other anywhere: an ordinary key")
        }
    }

    @Test
    fun `an ICU plural is not translated`() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"files_other": "{count, plural, one {# file} other {# files}}"}""")
        addFileToProject("locales/fr/common.json", """{}""")
        withDialog("common:files_other") { dialog ->
            dialog.translateEmptyLocales()

            Assertions.assertEquals("", dialog.valueOf("fr"))
            Assertions.assertTrue(asked.isEmpty())
            Assertions.assertTrue(dialog.translateMessage.contains("fr: an ICU plural is not translated"), dialog.translateMessage)
        }
    }

    // ── The plural form a key names, without the platform ─────────────────────

    @Test
    fun `the category and the other form of a key`() {
        Assertions.assertEquals("few" to "common:file_other", TranslationDialog.pluralFormOf("common:file_few", ".", "_"))
        Assertions.assertEquals("few" to "common:file.other", TranslationDialog.pluralFormOf("common:file.few", ".", "_"))
        Assertions.assertEquals("one" to "common:a.file_other", TranslationDialog.pluralFormOf("common:a.file_one", ".", "_"))
        Assertions.assertEquals(null, TranslationDialog.pluralFormOf("common:menu.home", ".", "_"))
        Assertions.assertEquals(null, TranslationDialog.pluralFormOf("common:first_name", ".", "_"))
    }
}
