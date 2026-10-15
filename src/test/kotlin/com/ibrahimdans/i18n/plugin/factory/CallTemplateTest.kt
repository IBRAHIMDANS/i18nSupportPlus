package com.ibrahimdans.i18n.plugin.factory

import com.ibrahimdans.i18n.plugin.ide.actions.ExtractionTestBase
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.utils.generator.translation.JsonTranslationGenerator
import com.intellij.openapi.ui.TestDialogManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A module's call template replaces the call its framework writes at extraction, so a project
 * calling its own wrapper gets `translate('…')` instead of `t('…')`.
 */
class CallTemplateTest : ExtractionTestBase() {

    private val translate = "translate({key})"

    private val json = JsonTranslationGenerator()

    @Test
    fun aTemplateHoldsTheKeyExactlyOnce() {
        assertTrue(CallTemplate.isValid(translate))
        assertFalse(CallTemplate.isValid("translate()"), "no {key}")
        assertFalse(CallTemplate.isValid("translate({key}, {key})"), "two {key}")
        assertFalse(CallTemplate.isValid(""))
    }

    @Test
    fun theTemplateReplacesTheCalleeAndKeepsTheArguments() {
        assertEquals("translate('a.b')", CallTemplate.apply(translate, "t('a.b')", "'a.b'", ""))
        assertEquals("translate('a.b')", CallTemplate.apply(translate, "i18n.t('a.b')", "'a.b'", ""))
        assertEquals("translate('a.b')", CallTemplate.apply(translate, "this.\$t('a.b')", "'a.b'", ""))
        assertEquals("tr('a.b')", CallTemplate.apply("tr({key})", "__('a.b')", "'a.b'", ""))
    }

    @Test
    fun theInterpolatedVariablesStayInTheArguments() {
        assertEquals(
            "translate('a.b', { name: user.name })",
            CallTemplate.apply(translate, "t('a.b', { name: user.name })", "'a.b'", "")
        )
    }

    @Test
    fun whatTheExtractorWritesAroundTheCallIsKept() {
        assertEquals("{translate('a.b', { name })}", CallTemplate.apply(translate, "{t('a.b', { name })}", "'a.b'", ""))
    }

    @Test
    fun aCallNotOnTheArgumentIsLeftAsWritten() {
        assertEquals("'a.b'", CallTemplate.apply(translate, "'a.b'", "'a.b'", ""))
    }

    private fun extract(path: String, module: ModuleConfig, patched: String) =
        myFixture.runWithConfig(config("json").copy(modules = listOf(module))) {
            runTestCase(
                path,
                "const a = \"I want to <caret>move it\";",
                patched,
                "assets/translation.json",
                json.generate("ref", arrayOf("section", "key", "Reference in json")),
                json.generate("ref", arrayOf("section", "key", "Reference in json"), arrayOf("value3", "I want to move it")),
                predefinedTextInputDialog("ref.value3")
            )
        }

    @Test
    fun theExtractionWritesTheModuleTemplate() =
        extract("simple.js", ModuleConfig(name = "app", rootDirectory = "src", callTemplate = translate), "const a = translate('ref.value3');")

    @Test
    fun anEmptyTemplateKeepsTheFrameworkCall() =
        extract("simple.js", ModuleConfig(name = "app", rootDirectory = "src"), "const a = i18n.t('ref.value3');")

    @Test
    fun anInvalidTemplateKeepsTheFrameworkCall() =
        extract("simple.js", ModuleConfig(name = "app", rootDirectory = "src", callTemplate = "translate()"), "const a = i18n.t('ref.value3');")

    @Test
    fun aFileOutsideTheModuleKeepsTheFrameworkCall() =
        extract("simple.js", ModuleConfig(name = "app", rootDirectory = "apps/web", callTemplate = translate), "const a = i18n.t('ref.value3');")

    @Test
    fun theTextGoesWhereTheTemplateAsksQuoted() {
        assertEquals("t('a.b', 'Hello')", CallTemplate.apply("t({key}, {text})", "t('a.b')", "'a.b'", "Hello"))
        assertEquals(
            "translate('a.b', { name }, 'It\\'s {{name}}')",
            CallTemplate.apply("translate({key}, {text})", "t('a.b', { name })", "'a.b'", "It's {{name}}")
        )
        assertEquals("t('a.b', 'one\\ntwo \\\\ end')", CallTemplate.apply("t({key}, {text})", "t('a.b')", "'a.b'", "one\ntwo \\ end"))
        assertEquals("tr('A', 'a.b', 'A')", CallTemplate.apply("tr({text}, {key}, {text})", "t('a.b')", "'a.b'", "A"))
    }

    @Test
    fun neitherTheArgumentsNorTheTextAreReadForPlaceholders() {
        assertEquals("t('{text}', '{key}')", CallTemplate.apply("t({key}, {text})", "t('{text}')", "'{text}'", "{key}"))
    }

    @Test
    fun aTemplateWithoutTextLeavesTheTextOut() {
        assertEquals("translate('a.b')", CallTemplate.apply(translate, "t('a.b')", "'a.b'", "Hello"))
    }

    /** Extracts the text at the caret of [source] as `account:save` through the extraction dialog. */
    private fun extractWithDialog(module: ModuleConfig, source: String, file: String, patched: String) =
        myFixture.runWithConfig(config("json").copy(modules = listOf(module))) {
            myFixture.addFileToProject("locales/en/account.json", "{}")
            myFixture.configureByText(file, source)
            TestDialogManager.setTestInputDialog(predefinedTextInputDialog("account:save"))
            myFixture.launchActionAndWait(myFixture.findSingleIntention(hint))
            myFixture.checkResult(patched)
        }

    private fun module(template: String, writeDefaultValue: Boolean = false) =
        ModuleConfig(name = "app", rootDirectory = "src", callTemplate = template, writeDefaultValue = writeDefaultValue)

    @Test
    fun theExtractionWritesTheTextWhereTheTemplateAsks() =
        extractWithDialog(
            module("translate({key}, {text})"), "const a = 'Save <caret>changes';", "save.js",
            "const a = translate('account:save', 'Save changes');"
        )

    @Test
    fun theTextKeepsItsPlaceholders() =
        extractWithDialog(
            module("translate({key}, {text})"), "export const greeting = (user) => 'Hel<caret>lo ' + user.name + '!';", "greeting.ts",
            "export const greeting = (user) => translate('account:save', { name: user.name }, 'Hello {{name}}!');"
        )

    @Test
    fun inJsxTheCallKeepsItsBraces() =
        extractWithDialog(
            module("t({key}, {text})"), "export const App = (t) => (<p>Save <caret>changes</p>);", "App.jsx",
            "export const App = (t) => (<p>{t('account:save', 'Save changes')}</p>);"
        )

    /** The module's default value option would write the text a second time. */
    @Test
    fun aTemplateWritingTheTextKeepsTheDefaultValueOptionOut() =
        extractWithDialog(
            module("translate({key}, {text})", writeDefaultValue = true), "const a = 'Save <caret>changes';", "save.js",
            "const a = translate('account:save', 'Save changes');"
        )

    @Test
    fun aTemplateWithoutTextLetsTheDefaultValueOptionWrite() =
        extractWithDialog(
            module(translate, writeDefaultValue = true), "const a = 'Save <caret>changes';", "save.js",
            "const a = translate('account:save', 'Save changes');"
        )
}
