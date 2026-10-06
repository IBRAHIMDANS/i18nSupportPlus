package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.JsCodeAndTranslationGenerators
import com.ibrahimdans.i18n.plugin.ide.JsonYamlCodeGenerators
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.utils.generator.code.CodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.code.ReactTransJsxAttrGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.JsonTranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.TranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource

class ExtractI18nIntentionActionTest: ExtractionTestBase() {

    @ParameterizedTest
    @ArgumentsSource(JsonYamlCodeGenerators::class)
    fun testKeyExtraction(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.runWithConfig(config(tg.ext())) {
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("<caret>I want to move it to translation"),
            cg.generate("'test:ref.avalue3'"),
            "assets/test.${tg.ext()}",
            tg.generate("ref", arrayOf("section", "key", "Reference in json")),
            tg.generate("ref", arrayOf("section", "key", "Reference in json"), arrayOf("avalue3", "I want to move it to translation")),
            predefinedTextInputDialog("test:ref.avalue3")
        )
    }

    @ParameterizedTest
    @ArgumentsSource(JsonYamlCodeGenerators::class)
    fun testKeyExtractionSortedFirst(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.runWithConfig(config(tg.ext(), true)) {
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("<caret>I want to move it to translation"),
            cg.generate("'test:ref.dvalue3'"),
            "assets/test.${tg.ext()}",
            tg.generate("ref", arrayOf("section", "key", "Reference in json")),
            tg.generate("ref", arrayOf("dvalue3", "I want to move it to translation"), arrayOf("section", "key", "Reference in json")),
            predefinedTextInputDialog("test:ref.dvalue3")
        )
    }

    @ParameterizedTest
    @ArgumentsSource(JsonYamlCodeGenerators::class)
    fun testKeyExtractionSortedMiddle(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.runWithConfig(config(tg.ext(), true)) {
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("Mid<caret>dle!!!"),
            cg.generate("'test:ref.mkey'"),
            "assets/test.${tg.ext()}",
            tg.generate("ref", arrayOf("akey", "The first one"), arrayOf("zkey", "The last one")),
            tg.generate("ref", arrayOf("akey", "The first one"), arrayOf("mkey", "Middle!!!"), arrayOf("zkey", "The last one")),
            predefinedTextInputDialog("test:ref.mkey")
        )
    }

    @ParameterizedTest
    @ArgumentsSource(JsonYamlCodeGenerators::class)
    fun testDefNsKeyExtraction(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.runWithConfig(config(tg.ext())) {
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("<caret>I want to move it to translation"),
            cg.generate("'ref.value3'"),
            "assets/translation.${tg.ext()}",
            tg.generate("ref", arrayOf("section", "key", "Reference in json")),
            tg.generate("ref", arrayOf("section", "key", "Reference in json"), arrayOf("value3", "I want to move it to translation")),
            predefinedTextInputDialog("ref.value3")
        )
    }

    @ParameterizedTest
    @ArgumentsSource(JsonYamlCodeGenerators::class)
    fun testRightBorderKeyExtraction(cg: CodeGenerator, tg: TranslationGenerator) = myFixture.runWithConfig(config(tg.ext())) {
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("I want to move it to translation<caret>"),
            cg.generate("'test:ref.value3'"),
            "assets/test.${tg.ext()}",
            tg.generate("ref", arrayOf("section", "key", "Reference in json")),
            tg.generate("ref", arrayOf("section", "key", "Reference in json"), arrayOf("value3", "I want to move it to translation")),
            predefinedTextInputDialog("test:ref.value3")
        )
    }

    // PHP excluded: bare string literals at file root are not valid PHP (missing <?php tags),
    // so the PHP extractor never offers "Extract i18n key" for such content.
    @ParameterizedTest
    @ArgumentsSource(JsCodeAndTranslationGenerators::class)
    fun testRootSource(cg: CodeGenerator, tg: TranslationGenerator) {
        myFixture.runWithConfig(config(tg.ext())) {
            runTestCase(
                "simple.${cg.ext()}",
                "\"I want to <caret>move it to translation\"",
                "i18n.t('test:ref.value3')",
                "assets/test.${tg.ext()}",
                tg.generate("ref", arrayOf("section", "key", "Reference in json")),
                tg.generate("ref", arrayOf("section", "key", "Reference in json"), arrayOf("value3", "I want to move it to translation")),
                predefinedTextInputDialog("test:ref.value3")
            )
        }
    }

    /**
     * `common:actions.save` already holds "Save": choosing it (the first option) points the code at
     * it and leaves the translation file untouched. The input dialog answers a key of its own, so a
     * creation reached by mistake would show in the file.
     */
    @ParameterizedTest
    @ArgumentsSource(JsonYamlCodeGenerators::class)
    fun testReusesExistingKey(cg: CodeGenerator, tg: TranslationGenerator) = withChoice(0, tg.ext()) {
        val translations = tg.generate("actions", arrayOf("save", "Save"))
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("<caret>Save"),
            cg.generate("'common:actions.save'"),
            "locales/en/common.${tg.ext()}",
            translations,
            translations,
            predefinedTextInputDialog("common:actions.created")
        )
    }

    /** The last option still creates a new key, through the same dialogs as when nothing matched. */
    @ParameterizedTest
    @ArgumentsSource(JsonYamlCodeGenerators::class)
    fun testCreatesNewKeyDespiteExistingOne(cg: CodeGenerator, tg: TranslationGenerator) = withChoice(1, tg.ext()) {
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("<caret>Save"),
            cg.generate("'common:actions.store'"),
            "locales/en/common.${tg.ext()}",
            tg.generate("actions", arrayOf("save", "Save")),
            tg.generate("actions", arrayOf("save", "Save"), arrayOf("store", "Save")),
            predefinedTextInputDialog("common:actions.store")
        )
    }

    /**
     * The *Cancel* button (after the key and *Create a new key…*) and Escape (-1) both abandon the
     * extraction: neither the code nor the translation file changes, and no key is asked for.
     */
    @ParameterizedTest
    @ArgumentsSource(JsonYamlCodeGenerators::class)
    fun testCancelButtonInExistingKeyChooser(cg: CodeGenerator, tg: TranslationGenerator) = runCancelledChoice(cg, tg, 2)

    @ParameterizedTest
    @ArgumentsSource(JsonYamlCodeGenerators::class)
    fun testEscapeInExistingKeyChooser(cg: CodeGenerator, tg: TranslationGenerator) = runCancelledChoice(cg, tg, -1)

    private fun runCancelledChoice(cg: CodeGenerator, tg: TranslationGenerator, answer: Int) {
        val translations = tg.generate("actions", arrayOf("save", "Save"))
        val src = cg.generateBlock("<caret>Save")
        withChoice(answer, tg.ext()) {
            runTestCase(
                "simple.${cg.ext()}",
                src,
                src.replace("<caret>", ""),
                "locales/en/common.${tg.ext()}",
                translations,
                translations,
                predefinedTextInputDialog("common:actions.created")
            )
        }
    }

    /**
     * Four keys hold "Save": three are offered as buttons and the message counts the fourth, so
     * the fourth button is *Create a new key…* rather than a key.
     */
    @ParameterizedTest
    @ArgumentsSource(JsonYamlCodeGenerators::class)
    fun testCapsOfferedKeys(cg: CodeGenerator, tg: TranslationGenerator) {
        val keys = arrayOf(arrayOf("save", "Save"), arrayOf("store", "Save"), arrayOf("submit", "Save"), arrayOf("keep", "Save"))
        val expectedMessage = PluginBundle.message("action.intention.extract.key.reuse.message", "Save") +
            "\n" + PluginBundle.message("action.intention.extract.key.reuse.more", 1)
        withChoice(3, tg.ext(), expectedMessage) {
            runTestCase(
                "simple.${cg.ext()}",
                cg.generateBlock("<caret>Save"),
                cg.generate("'common:actions.write'"),
                "locales/en/common.${tg.ext()}",
                tg.generate("actions", *keys),
                tg.generate("actions", *keys, arrayOf("write", "Save")),
                predefinedTextInputDialog("common:actions.write")
            )
        }
    }

    /**
     * Answers the "already translated" chooser with button [index], for [block] only: the dialog
     * set through [TestDialogManager] outlives the test otherwise. The translation files live in
     * `locales/en/`, the layout the source scan reads a locale from; the fixture cannot create two
     * directory levels at once, so they are created first.
     */
    private fun withChoice(
        index: Int,
        ext: String,
        expectedMessage: String = PluginBundle.message("action.intention.extract.key.reuse.message", "Save"),
        block: () -> Unit
    ) {
        val messages = mutableListOf<String>()
        val previous = TestDialogManager.setTestDialog(TestDialog { message -> messages += message; index })
        try {
            myFixture.tempDirFixture.findOrCreateDir("locales/en")
            myFixture.runWithConfig(config(ext), block)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        Assertions.assertEquals(expectedMessage, messages.firstOrNull(), "the existing key was not offered")
    }

    @Test
    fun testRootSource2() {
        val tg = JsonTranslationGenerator()
        val cg = ReactTransJsxAttrGenerator()
        myFixture.runWithConfig(config(tg.ext())) {
            runTestCase(
                    "simple.${cg.ext()}",
                    cg.generateBlock("\"I want to <caret>move it to translation\""),
                    cg.generateBlock("{i18n.t('test:ref.value3')}"),
                    "assets/test.${tg.ext()}",
                    tg.generate("ref", arrayOf("section", "key", "Reference in json")),
                    tg.generate("ref", arrayOf("section", "key", "Reference in json"), arrayOf("value3", "I want to move it to translation")),
                    predefinedTextInputDialog("test:ref.value3")
            )
        }
    }
}