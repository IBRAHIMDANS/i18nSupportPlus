package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.PhpGetTextCodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.PoTranslationGenerator
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Key extraction on a PHP GetText project — writing the extracted key into a `.po` catalogue.
 *
 * These run under `Config(gettext = true)`, which is what a GetText project sets: the extracted
 * call must then read `gettext('…')`, not `t('…')`, and the key must land in the `.po` as a
 * `msgid` / `msgstr` pair.
 */
class ExtractI18nPhpGettextTest: ExtractionTestBase() {

    private val tg = PoTranslationGenerator()
    private val cg = PhpGetTextCodeGenerator("gettext")

    @Test
    fun testKeyExtraction() = myFixture.runWithConfig(Config(gettext = true)) {
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("<caret>I want to move it to translation"),
            cg.generate("'ref.avalue3'"),
            "en-US/LC_MESSAGES/test.${tg.ext()}",
            tg.generate(arrayOf("ref.section", "key", "Reference in json")),
            tg.generate(arrayOf("ref.section", "key", "Reference in json"), arrayOf("ref.avalue3", "I want to move it to translation")) + "\n",
            predefinedTextInputDialog("ref.avalue3")
        )
    }

    /**
     * Disabled: `extractSorted` is not implemented for PO. `JsonLocalization`, `TsContentGenerator`
     * and `YamlLocalization` each place the new node against a sorted anchor, while
     * `PlainObjectContentGenerator.generateTranslationEntry` always appends at the end of the
     * document. The expectations below state where the entry *should* land once it is.
     */
    @Disabled
    @Test
    fun testKeyExtractionSortedFirst() = myFixture.runWithConfig(Config(gettext = true, extractSorted = true)) {
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("<caret>I want to move it to translation"),
            cg.generate("'ref.dvalue3'"),
            "en-US/LC_MESSAGES/test.${tg.ext()}",
            tg.generate(arrayOf("ref.section", "key", "Reference in json")),
            tg.generate(arrayOf("ref.dvalue3", "I want to move it to translation"), arrayOf("ref.section", "key", "Reference in json")) + "\n",
            predefinedTextInputDialog("ref.dvalue3")
        )
    }

    /**
     * Disabled: `extractSorted` is not implemented for PO. `JsonLocalization`, `TsContentGenerator`
     * and `YamlLocalization` each place the new node against a sorted anchor, while
     * `PlainObjectContentGenerator.generateTranslationEntry` always appends at the end of the
     * document. The expectations below state where the entry *should* land once it is.
     */
    @Disabled
    @Test
    fun testKeyExtractionSortedMiddle() = myFixture.runWithConfig(Config(gettext = true, extractSorted = true)) {
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("Mid<caret>dle!!!"),
            cg.generate("'ref.mkey'"),
            "en-US/LC_MESSAGES/test.${tg.ext()}",
            tg.generate(arrayOf("ref.akey", "The first one"), arrayOf("zkey", "The last one")),
            tg.generate(arrayOf("ref.akey", "The first one"), arrayOf("ref.mkey", "Middle!!!"), arrayOf("zkey", "The last one")) + "\n",
            predefinedTextInputDialog("ref.mkey")
        )
    }

    @Test
    fun testDefNsKeyExtraction() = myFixture.runWithConfig(Config(gettext = true)) {
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("<caret>I want to move it to translation"),
            cg.generate("'ref.value3'"),
            "en-US/LC_MESSAGES/translation.${tg.ext()}",
            tg.generate(arrayOf("ref.section", "key", "Reference in json")),
            tg.generate(arrayOf("ref.section", "key", "Reference in json"), arrayOf("ref.value3", "I want to move it to translation")) + "\n",
            predefinedTextInputDialog("ref.value3")
        )
    }

    @Test
    fun testRightBorderKeyExtraction() = myFixture.runWithConfig(Config(gettext = true)) {
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("I want to move it to translation<caret>"),
            cg.generate("'ref.value3'"),
            "en-US/LC_MESSAGES/test.${tg.ext()}",
            tg.generate(arrayOf("ref.section", "key", "Reference in json")),
            tg.generate(arrayOf("ref.section", "key", "Reference in json"), arrayOf("ref.value3", "I want to move it to translation")) + "\n",
            predefinedTextInputDialog("ref.value3")
        )
    }

}