package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.openapi.ui.TestDialogManager
import org.junit.jupiter.api.Test

/**
 * *Extract I18n* in a react-intl project writes react-intl's calls: `<FormattedMessage>` for a JSX
 * text, `intl.formatMessage` for an attribute or a string — never `i18n.t`, which react-intl lacks.
 * A string with no `useIntl()` in scope is left as it is, rather than calling an `intl` that is not there.
 * A text that became a `<FormattedMessage>` gets its import, merged into the file's `react-intl` one.
 */
class ReactIntlExtractionTest : ExtractionTestBase() {

    private fun module(preset: String = "react-intl", writeDefaultValue: Boolean = false, callTemplate: String = "") =
        ModuleConfig(name = "app", rootDirectory = "src", preset = preset, writeDefaultValue = writeDefaultValue, callTemplate = callTemplate)

    /** Extracts the text at the caret of [source] as [key]; [modules] empty for a project without any. */
    private fun extract(
        source: String,
        patched: String,
        key: String = "account:save",
        file: String = "App.jsx",
        modules: List<ModuleConfig> = listOf(module()),
    ) = myFixture.runWithConfig(config("json").copy(modules = modules)) {
        myFixture.addFileToProject("locales/en/account.json", "{}")
        myFixture.configureByText(file, source)
        TestDialogManager.setTestInputDialog(predefinedTextInputDialog(key))
        myFixture.launchActionAndWait(myFixture.findSingleIntention(hint))

        myFixture.checkResult(patched)
    }

    @Test
    fun aJsxTextBecomesAFormattedMessage() = extract(
        "export const App = () => (<p>Save <caret>changes</p>);",
        "import { FormattedMessage } from 'react-intl';\nexport const App = () => (<p><FormattedMessage id=\"account:save\" /></p>);"
    )

    @Test
    fun itsVariablesGoInValues() = extract(
        "export const App = ({ user }) => (<p>Hel<caret>lo {user.name}!</p>);",
        "import { FormattedMessage } from 'react-intl';\nexport const App = ({ user }) => (<p><FormattedMessage id=\"account:greeting\" values={{ name: user.name }} /></p>);",
        key = "account:greeting"
    )

    @Test
    fun theTextIsItsDefaultMessageWhenTheModuleAsks() = extract(
        "export const App = () => (<p>Save <caret>changes</p>);",
        "import { FormattedMessage } from 'react-intl';\nexport const App = () => (<p><FormattedMessage id=\"account:save\" defaultMessage=\"Save changes\" /></p>);",
        modules = listOf(module(writeDefaultValue = true))
    )

    @Test
    fun anAttributeCallsFormatMessage() = extract(
        "export const App = () => {\n    const intl = useIntl();\n    return <input placeholder=\"Your <caret>name\" />;\n};",
        "export const App = () => {\n    const intl = useIntl();\n    return <input placeholder={intl.formatMessage({ id: 'account:name' })} />;\n};",
        key = "account:name"
    )

    @Test
    fun theNameUseIntlIsGivenIsKept() = extract(
        "export function useLabel() {\n    const i = useIntl();\n    return 'Save <caret>changes';\n}",
        "export function useLabel() {\n    const i = useIntl();\n    return i.formatMessage({ id: 'account:save' });\n}",
        file = "label.js"
    )

    @Test
    fun aDestructuredFormatMessageIsCalledAsIs() = extract(
        "export function useLabel(count) {\n    const { formatMessage } = useIntl();\n    return `${'$'}{count} fi<caret>les`;\n}",
        "export function useLabel(count) {\n    const { formatMessage } = useIntl();\n    return formatMessage({ id: 'account:files' }, { count });\n}",
        key = "account:files",
        file = "label.js"
    )

    @Test
    fun withoutUseIntlAStringIsLeftAsItIs() = extract(
        "export function label() {\n    return 'Save <caret>changes';\n}",
        "export function label() {\n    return 'Save changes';\n}",
        file = "label.js"
    )

    @Test
    fun withoutUseIntlATextStillBecomesAFormattedMessage() = extract(
        "export function App() {\n    return <p>Save <caret>changes</p>;\n}",
        "import { FormattedMessage } from 'react-intl';\nexport function App() {\n    return <p><FormattedMessage id=\"account:save\" /></p>;\n}"
    )

    /** No module: the file importing react-intl is what says so. */
    @Test
    fun aFileImportingReactIntlIsReactIntlWithoutAnyModule() = extract(
        "import { FormattedMessage } from 'react-intl';\nexport const App = () => (<p>Save <caret>changes</p>);",
        "import { FormattedMessage } from 'react-intl';\nexport const App = () => (<p><FormattedMessage id=\"account:save\" /></p>);",
        modules = emptyList()
    )

    @Test
    fun aModuleCallTemplateWins() = extract(
        "export function label() {\n    return 'Save <caret>changes';\n}",
        "export function label() {\n    return translate('account:save');\n}",
        file = "label.js",
        modules = listOf(module(callTemplate = "translate({key})"))
    )

    @Test
    fun anI18nextModuleKeepsItsCall() = extract(
        "export const App = () => (<p>Save <caret>changes</p>);",
        "export const App = () => (<p>{i18n.t('account:save')}</p>);",
        modules = listOf(module(preset = "i18next"))
    )

    @Test
    fun formattedMessageJoinsTheFileReactIntlImport() = extract(
        "import { useIntl } from 'react-intl';\nexport const App = () => (<p>Save <caret>changes</p>);",
        "import { FormattedMessage, useIntl } from 'react-intl';\nexport const App = () => (<p><FormattedMessage id=\"account:save\" /></p>);"
    )

    @Test
    fun theImportComesAfterTheUseClientDirective() = extract(
        "'use client';\nexport const App = () => (<p>Save <caret>changes</p>);",
        "'use client';\nimport { FormattedMessage } from 'react-intl';\nexport const App = () => (<p><FormattedMessage id=\"account:save\" /></p>);"
    )
}
