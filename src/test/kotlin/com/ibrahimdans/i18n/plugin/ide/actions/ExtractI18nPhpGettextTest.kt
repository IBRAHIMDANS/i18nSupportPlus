package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.utils.generator.code.PhpGetTextCodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.PoTranslationGenerator
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Key extraction on a PHP GetText project — writing the extracted key into a `.po` catalogue.
 *
 * Disabled: the plugin does not do this yet, and these cases state what it would have to do.
 * Two things are missing, both verified by running them:
 *
 *  1. [com.ibrahimdans.i18n.extensions.lang.php.PhpTranslationExtractor] hardcodes its call
 *     template to `t($it)` and never consults `Config.gettextAliases`, so the extracted code
 *     reads `t('ref.avalue3')` where a GetText project expects `gettext('ref.avalue3')`.
 *  2. The `.po` file is left untouched. The pieces are all in place — the GNU GetText plugin
 *     is loaded in the test sandbox, `PlainObjectLocalization` claims the `Locale` file type,
 *     `findSources` returns the catalogue and `PlainObjectContentGenerator` is fully written —
 *     so the break sits somewhere between `CreateKeyQuickFix.createPropertyInFile` and the
 *     insertion itself. Note that the generator writes into the Document without saving it.
 *
 * They are kept as code rather than as a commented-out block so that they still compile and
 * stay readable: what stood here before was a commented block topped by an empty
 * `testKeyExtraction() {}`, which reported a green test of that name while the real one was
 * disabled. Re-enable once extraction supports GetText; see
 * [com.ibrahimdans.i18n.plugin.ide.references.translation.PoToPhpReferenceTest], disabled
 * for the neighbouring gap on PO→code navigation.
 */
@Disabled
class ExtractI18nPhpGettextTest: ExtractionTestBase() {

    private val tg = PoTranslationGenerator()
    private val cg = PhpGetTextCodeGenerator("gettext")

    @Test
    fun testKeyExtraction() = myFixture.runWithConfig(config(tg.ext())) {
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("<caret>I want to move it to translation"),
            cg.generate("'ref.avalue3'"),
            "en-US/LC_MESSAGES/test.${tg.ext()}",
            tg.generate(arrayOf("ref.section", "key", "Reference in json")),
            tg.generate(arrayOf("ref.section", "key", "Reference in json"), arrayOf("avalue3", "I want to move it to translation")),
            predefinedTextInputDialog("ref.avalue3")
        )
    }

    @Test
    fun testKeyExtractionSortedFirst() = myFixture.runWithConfig(config(tg.ext(), true)) {
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("<caret>I want to move it to translation"),
            cg.generate("'ref.dvalue3'"),
            "en-US/LC_MESSAGES/test.${tg.ext()}",
            tg.generate(arrayOf("ref.section", "key", "Reference in json")),
            tg.generate(arrayOf("ref.dvalue3", "I want to move it to translation"), arrayOf("section", "key", "Reference in json")),
            predefinedTextInputDialog("ref.dvalue3")
        )
    }

    @Test
    fun testKeyExtractionSortedMiddle() = myFixture.runWithConfig(config(tg.ext(), true)) {
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("Mid<caret>dle!!!"),
            cg.generate("'ref.mkey'"),
            "en-US/LC_MESSAGES/test.${tg.ext()}",
            tg.generate(arrayOf("ref.akey", "The first one"), arrayOf("zkey", "The last one")),
            tg.generate(arrayOf("ref.akey", "The first one"), arrayOf("mkey", "Middle!!!"), arrayOf("zkey", "The last one")),
            predefinedTextInputDialog("ref.mkey")
        )
    }

    @Test
    fun testDefNsKeyExtraction() = myFixture.runWithConfig(config(tg.ext())) {
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("<caret>I want to move it to translation"),
            cg.generate("'ref.value3'"),
            "en-US/LC_MESSAGES/translation.${tg.ext()}",
            tg.generate(arrayOf("ref.section", "key", "Reference in json")),
            tg.generate(arrayOf("ref.section", "key", "Reference in json"), arrayOf("value3", "I want to move it to translation")),
            predefinedTextInputDialog("ref.value3")
        )
    }

    @Test
    fun testRightBorderKeyExtraction() = myFixture.runWithConfig(config(tg.ext())) {
        runTestCase(
            "simple.${cg.ext()}",
            cg.generateBlock("I want to move it to translation<caret>"),
            cg.generate("'ref.value3'"),
            "en-US/LC_MESSAGES/test.${tg.ext()}",
            tg.generate(arrayOf("ref.section", "key", "Reference in json")),
            tg.generate(arrayOf("ref.section", "key", "Reference in json"), arrayOf("value3", "I want to move it to translation")),
            predefinedTextInputDialog("ref.value3")
        )
    }

    @Test
    fun testRootSource() {
        myFixture.runWithConfig(config(tg.ext())) {
            runTestCase(
                "simple.${cg.ext()}",
                "I want to <caret>move it to translation",
                "i18n.t<caret>('ref.value3')",
                "en-US/LC_MESSAGES/test.${tg.ext()}",
                tg.generate(arrayOf("ref.section", "key", "Reference in json")),
                tg.generate(arrayOf("ref.section", "key", "Reference in json"), arrayOf("value3", "I want to move it to translation")),
                predefinedTextInputDialog("ref.value3")
            )
        }
    }
}