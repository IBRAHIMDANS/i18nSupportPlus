package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A module's *Write the default value in the call* puts the text extracted into the call, as
 * i18next's default value: `t('key', 'Text')`, or its `defaultValue` option when the call passes
 * variables. Off, the call is the one written before the option existed.
 */
class ExtractDefaultValueTest : ExtractionTestBase() {

    private fun module(writeDefaultValue: Boolean, preset: String = "") =
        ModuleConfig(name = "app", rootDirectory = "src", preset = preset, writeDefaultValue = writeDefaultValue)

    /** Extracts the text at the caret of [source] as `account:save`, in a module with [module]'s settings. */
    private fun extract(module: ModuleConfig, patched: String, source: String = "const a = 'Save <caret>changes';", file: String = "save.js") =
        myFixture.runWithConfig(config("json").copy(modules = listOf(module))) {
            myFixture.addFileToProject("locales/en/account.json", "{}")
            myFixture.configureByText(file, source)
            TestDialogManager.setTestInputDialog(predefinedTextInputDialog("account:save"))
            myFixture.launchActionAndWait(myFixture.findSingleIntention(hint))

            myFixture.checkResult(patched)
        }

    @Test
    fun offTheCallIsUnchanged() = extract(module(writeDefaultValue = false), "const a = i18n.t('account:save');")

    @Test
    fun onTheTextIsTheSecondArgument() =
        extract(module(writeDefaultValue = true), "const a = i18n.t('account:save', 'Save changes');")

    @Test
    fun anI18nextModuleWritesItToo() =
        extract(module(writeDefaultValue = true, preset = "i18next"), "const a = i18n.t('account:save', 'Save changes');")

    /** react-intl's `formatMessage(descriptor, values)` has no second string argument to receive it. */
    @Test
    fun anotherFrameworkKeepsTheCall() =
        extract(module(writeDefaultValue = true, preset = "react-intl"), "const a = i18n.t('account:save');")

    @Test
    fun inJsxTheCallKeepsItsBraces() =
        extract(
            module(writeDefaultValue = true),
            "export const App = (i18n) => (<p>{i18n.t('account:save', 'Save changes')}</p>);",
            "export const App = (i18n) => (<p>Save <caret>changes</p>);",
            "App.jsx"
        )

    @Test
    fun withVariablesTheTextIsTheDefaultValueOption() =
        myFixture.runWithConfig(config("json").copy(modules = listOf(module(writeDefaultValue = true)))) {
            myFixture.addFileToProject("locales/en/account.json", "{}")
            myFixture.configureByText("greeting.ts", "export const greeting = (user) => 'Hel<caret>lo ' + user.name + '!';")
            TestDialogManager.setTestInputDialog(predefinedTextInputDialog("account:greeting"))
            myFixture.launchActionAndWait(myFixture.findSingleIntention(hint))

            myFixture.checkResult(
                "export const greeting = (user) => i18n.t('account:greeting', { defaultValue: 'Hello {{name}}!', name: user.name });"
            )
        }

    /** A variable renamed in the dialog is renamed in the default value, as in the files. */
    @Test
    fun theDefaultValueFollowsARenamedVariable() {
        val name = MessageVariable("name", "user.name", "{{name}}")
        val model = ExtractKeyModel(
            text = "Hello {{name}}!",
            existingKeys = emptyList(),
            namespaces = listOf("common"),
            sourcesByNamespace = emptyMap(),
            keysByNamespace = emptyMap(),
            referenceLocale = "en",
            config = Config(defaultNs = "common"),
            call = { argument, variables -> "t($argument${options(variables)})" },
            variables = listOf(name),
            writesDefaultValue = true,
        )
        assertEquals(
            "t('greeting', { defaultValue: 'Hello {{userName}}!', userName: user.name })",
            model.preview("common", "greeting", listOf(name.renamed("userName")))
        )
    }

    /** What the JavaScript extractors append: `, { name: user.name }`. */
    private fun options(variables: List<MessageVariable>): String =
        if (variables.isEmpty()) "" else ", { " + variables.joinToString(", ") { "${it.name}: ${it.expression}" } + " }"

    @Test
    fun theTextIsQuotedAsAJavaScriptString() {
        assertEquals("""'It\'s'""", ExtractKeyModel.quote("It's"))
        assertEquals("""'a\\b\nc'""", ExtractKeyModel.quote("a\\b\nc"))
    }

    @Test
    fun theOptionSurvivesARoundTrip() {
        val loaded = XmlSerializer.deserialize(
            XmlSerializer.serialize(ModuleConfig(name = "app", writeDefaultValue = true)),
            ModuleConfig::class.java
        )
        assertTrue(loaded.writeDefaultValue)
    }

    @Test
    fun theOptionIsOffByDefault() {
        assertFalse(ModuleConfig().writeDefaultValue)
        assertFalse(XmlSerializer.deserialize(XmlSerializer.serialize(ModuleConfig(name = "app")), ModuleConfig::class.java).writeDefaultValue)
    }
}
