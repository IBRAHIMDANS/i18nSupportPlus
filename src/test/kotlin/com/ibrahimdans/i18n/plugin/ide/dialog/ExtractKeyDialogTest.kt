package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.extensions.lang.js.MessageVariables
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractAnswer
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractKeyModel
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Disposer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Drives the real dialog. The extraction tests answer it through a script, so what the dialog
 * itself does — what it opens on, how it follows each edit, what OK returns — is pinned here.
 * Building it also guards the panel: radio buttons outside a `buttonsGroup` threw in the
 * constructor, and the dialog never opened.
 *
 * The fixture mirrors cbox-front: namespaces per feature, nested keys, and a component whose
 * keys all sit under `account:myAccount.myTrustees`.
 */
class ExtractKeyDialogTest : PlatformBaseTest() {

    private val name = MessageVariable("name", "user.name", "{{name}}")
    private val count = MessageVariable("count", "files.length", "{{count}}")

    /** Opens the dialog on [text] extracted from a component, runs [block] on it, then disposes it. */
    private fun withDialog(
        text: String = "test extract",
        variables: List<MessageVariable> = emptyList(),
        existingKeys: List<String> = emptyList(),
        block: (ExtractKeyDialog) -> Unit,
    ) {
        addFileToProject(
            "locales/en/account.json",
            """{"myAccount": {"help": "Help", "myTrustees": {"title": "Trustees", "noTrustee": {"title": "None"}}}}"""
        )
        addFileToProject("locales/fr/account.json", "{}")
        addFileToProject("locales/en/common.json", """{"button": {"cancel": "Cancel"}}""")
        addFileToProject("locales/fr/common.json", "{}")
        val file = myFixture.configureByText(
            "MyTrusteesTab.tsx",
            """
                export const MyTrusteesTab = () => {
                    const { t } = useTranslation();
                    return <div><p>{t('account:myAccount.myTrustees.title')}</p><p>{t('account:myAccount.myTrustees.noTrustee.title')}</p></div>;
                };
            """.trimIndent()
        )
        val model = ReadAction.compute<ExtractKeyModel, RuntimeException> {
            ExtractKeyModel.load(
                project, file, text, existingKeys,
                { argument, passed -> "{i18n.t($argument${MessageVariables.options(passed)})}" },
                variables
            )
        }
        val dialog = ExtractKeyDialog(project, model, file)
        try {
            block(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    @Test
    fun opensWhereTheKeysAroundTheTextLive() = withDialog { dialog ->
        assertEquals("account", dialog.namespaceCombo.selectedItem)
        assertEquals("myAccount.myTrustees", dialog.parentField.text)
        assertEquals("testExtract", dialog.keyField.text)
        assertEquals(mapOf("en" to "test extract", "fr" to ""), dialog.typedValues())
        assertEquals("{i18n.t('account:myAccount.myTrustees.testExtract')}", dialog.previewLabel.text)
        assertEquals(null, dialog.validationError())
    }

    /** Another namespace: its files, no parent guessed there, and the key previewed in it. */
    @Test
    fun switchingTheNamespaceFollowsIt() = withDialog { dialog ->
        dialog.namespaceCombo.selectedItem = "common"

        assertEquals("", dialog.parentField.text)
        assertEquals(setOf("en", "fr"), dialog.typedValues().keys)
        assertEquals("{i18n.t('common:testExtract')}", dialog.previewLabel.text)
    }

    /** A name the user typed is theirs: a namespace change does not replace it. */
    @Test
    fun aTypedNameSurvivesANamespaceChange() = withDialog { dialog ->
        dialog.keyField.text = "myOwnName"
        dialog.namespaceCombo.selectedItem = "common"
        assertEquals("myOwnName", dialog.keyField.text)
    }

    @Test
    fun renamingAVariableRenamesItsPlaceholderEverywhere() = withDialog("Hello {{name}}", listOf(name)) { dialog ->
        dialog.variableFields.single().text = "userName"

        assertEquals("Hello {{userName}}", dialog.typedValues()["en"])
        assertEquals("{i18n.t('account:myAccount.myTrustees.helloName', { userName: user.name })}", dialog.previewLabel.text)
        dialog.confirm()
        assertEquals(listOf(name.renamed("userName")), (dialog.answer as ExtractAnswer.Create).variables)
    }

    @Test
    fun pluralFormsGiveEachLocaleItsForms() = withDialog("{{count}} files", listOf(count)) { dialog ->
        assertTrue(dialog.pluralBox.isVisible, "a {{count}} offers plural forms")
        dialog.pluralBox.doClick()

        assertEquals(
            mapOf("en.one" to "{{count}} files", "en.other" to "{{count}} files", "fr.one" to "", "fr.other" to ""),
            dialog.typedValues()
        )
        dialog.confirm()
        assertEquals(setOf("one", "other"), (dialog.answer as ExtractAnswer.Create).plurals.values.first().keys)
    }

    /** Renamed, `count` no longer selects a form: plural forms are withdrawn, single values back. */
    @Test
    fun renamingCountWithdrawsPluralForms() = withDialog("{{count}} files", listOf(count)) { dialog ->
        dialog.pluralBox.doClick()
        dialog.variableFields.single().text = "total"

        assertFalse(dialog.pluralBox.isVisible)
        assertEquals(mapOf("en" to "{{total}} files", "fr" to ""), dialog.typedValues())
    }

    @Test
    fun okRefusesWhatCannotBeWritten() = withDialog(variables = listOf(name)) { dialog ->
        dialog.parentField.text = "myAccount.help"
        assertEquals(PluginBundle.message("dialog.extract.conflict.leaf", "myAccount.help"), dialog.validationError())

        dialog.parentField.text = "myAccount"
        dialog.keyField.text = "myTrustees"
        assertEquals(PluginBundle.message("dialog.extract.conflict.group", "myAccount.myTrustees"), dialog.validationError())

        dialog.keyField.text = "fine"
        dialog.variableFields.single().text = "user name"
        assertEquals(PluginBundle.message("dialog.extract.variables.invalid", "user name"), dialog.validationError())

        dialog.variableFields.single().text = "name"
        dialog.keyField.text = ""
        assertEquals(PluginBundle.message("dialog.translation.error.key.empty"), dialog.validationError())
    }

    @Test
    fun okReturnsTheKeyTheValuesAndTheEmptyLocalesChoice() = withDialog { dialog ->
        dialog.copyReferenceButton.doClick()
        dialog.confirm()

        val answer = dialog.answer as ExtractAnswer.Create
        assertEquals("account", answer.namespace)
        assertEquals("myAccount.myTrustees.testExtract", answer.key)
        assertEquals(mapOf("en" to "test extract", "fr" to ""), answer.values.mapKeys { it.key.localeLabel() })
        assertTrue(answer.copyReference)
    }

    /** One key holding the text is the obvious intent: OK reuses it, writing nothing. */
    @Test
    fun aSingleExistingKeyIsReusedByDefault() = withDialog(existingKeys = listOf("common:button.cancel")) { dialog ->
        assertTrue(dialog.reuseButtons.single().isSelected)
        assertEquals("{i18n.t('common:button.cancel')}", dialog.previewLabel.text)
        assertFalse(dialog.keyField.isEnabled)
        dialog.confirm()
        assertEquals(ExtractAnswer.Reuse("common:button.cancel"), dialog.answer)
    }

    /** Several are a choice to make: *Create* is selected, the key fields usable until a key is picked. */
    @Test
    fun severalExistingKeysLeaveTheChoiceToTheUser() =
        withDialog(existingKeys = listOf("common:button.cancel", "account:cancel")) { dialog ->
            assertTrue(dialog.createButton.isSelected)
            assertTrue(dialog.keyField.isEnabled)
            dialog.reuseButtons[1].doClick()
            assertFalse(dialog.keyField.isEnabled)
            assertEquals("{i18n.t('account:cancel')}", dialog.previewLabel.text)
        }
}
