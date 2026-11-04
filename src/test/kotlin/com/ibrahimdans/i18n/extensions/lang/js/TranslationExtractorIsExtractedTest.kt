package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractionCandidates
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.psi.PsiElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which strings the extractors hold as already extracted — the key of a translation call — so
 * that *Extract I18n*, *Batch Extract* and *Internationalize…* do not offer them. Only `t('…')`
 * used to count: a key in `$t('…')`, `i18n._('…')`, `formatMessage({ id })` or the wrapper of a
 * module's call template was offered and, accepted, became a translation of itself.
 */
class TranslationExtractorIsExtractedTest : PlatformBaseTest() {

    private val js = JsTranslationExtractor()
    private val jsx = JsxTranslationExtractor()

    /** The leaf of the string `save` in [code], written in a file named [name]. */
    private fun saveIn(name: String, code: String): PsiElement {
        val file = myFixture.configureByText(name, code)
        return file.findElementAt(file.text.indexOf("save"))!!
    }

    private fun jsExtracted(code: String): Boolean = js.isExtracted(saveIn("labels.js", code))

    /** A module over the fixture's `src` directory, where every file it opens lives. */
    private fun module(preset: String = "", callTemplate: String = "") =
        ModuleConfig(name = "app", rootDirectory = "src", preset = preset, callTemplate = callTemplate)

    @Test
    fun theKeyOfTIsExtracted() {
        assertTrue(jsExtracted("const a = t('save');"))
        assertTrue(jsExtracted("const a = i18n.t('save');"))
        assertTrue(jsExtracted("const a = t(`save`);"))
    }

    @Test
    fun theKeyOfVueI18nDollarTIsExtracted() {
        assertTrue(jsExtracted("const a = \$t('save');"))
        assertTrue(jsExtracted("const a = this.\$t('save');"))
    }

    @Test
    fun theKeyOfLinguiUnderscoreIsExtracted() {
        assertTrue(jsExtracted("const a = i18n._('save');"))
    }

    @Test
    fun theIdOfAReactIntlDescriptorIsExtracted() {
        assertTrue(jsExtracted("const a = intl.formatMessage({ id: 'save' });"))
        assertTrue(jsExtracted("const a = formatMessage({ id: 'save', defaultMessage: 'Save' });"))
    }

    /** The module's wrapper is nobody's function name: the template is what makes it a translation call. */
    @Test
    fun theKeyOfTheModuleCallTemplateIsExtracted() {
        assertFalse(jsExtracted("const a = translate('save');"), "no template")
        myFixture.runWithConfig(Config(modules = listOf(module(callTemplate = "translate({key}, {text})")))) {
            assertTrue(jsExtracted("const a = translate('save');"))
            assertTrue(jsExtracted("const a = translate('save', 'Save');"))
        }
        // The key after the text: whatever the wrapper takes was written by the extraction.
        myFixture.runWithConfig(Config(modules = listOf(module(callTemplate = "tr({text}, {key})")))) {
            assertTrue(jsExtracted("const a = tr('Save', 'save');"))
        }
    }

    /** A module preset keeps its framework's calls only, as the annotator does; a bare `t` stays one everywhere. */
    @Test
    fun aPresetRestrictsTheCallsToItsFramework() {
        myFixture.runWithConfig(Config(modules = listOf(module(preset = "i18next")))) {
            assertFalse(jsExtracted("const a = \$t('save');"))
            assertTrue(jsExtracted("const a = i18n.t('save');"))
        }
        myFixture.runWithConfig(Config(modules = listOf(module(preset = "vue-i18n")))) {
            assertTrue(jsExtracted("const a = \$t('save');"))
            assertTrue(jsExtracted("const a = t('save');"))
        }
    }

    @Test
    fun otherStringsAreNotExtracted() {
        assertFalse(jsExtracted("const a = 'save';"))
        assertFalse(jsExtracted("const a = toast.t('save');"), "a `t` of another object")
        assertFalse(jsExtracted("const a = t('key', 'save');"), "a default value, not the key")
        assertFalse(jsExtracted("const a = notify('save');"))
    }

    @Test
    fun aJsxExpressionFollowsTheSameCalls() {
        assertTrue(jsx.isExtracted(saveIn("App.jsx", "export const App = () => <p>{\$t('save')}</p>;")))
        assertTrue(jsx.isExtracted(saveIn("App.jsx", "export const App = () => <p>{t('save')}</p>;")))
        assertFalse(jsx.isExtracted(saveIn("App.jsx", "export const App = () => <p>save</p>;")))
        assertFalse(jsx.isExtracted(saveIn("App.jsx", "export const App = () => <p>{notify('save')}</p>;")))
    }

    /** What reaches *Batch Extract* and *Internationalize…*: the strings left are the hard-coded ones. */
    @Test
    fun theCandidatesLeaveTheExtractedKeysOut() =
        myFixture.runWithConfig(Config(modules = listOf(module(callTemplate = "translate({key})")))) {
            myFixture.configureByText(
                "labels.js",
                """
                    const a = ${'$'}t('a.key');
                    const b = i18n._('b.key');
                    const c = intl.formatMessage({ id: 'c.key' });
                    const d = translate('d.key');
                    const e = 'Hard-coded';
                """.trimIndent()
            )
            assertEquals(listOf("Hard-coded"), ExtractionCandidates.collect(myFixture.file).map { it.originalText })
        }
}
