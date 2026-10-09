package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractKeyModel
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Disposer
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Builds the real dialog. The extraction tests answer it through a script and never construct
 * it, so a panel the UI DSL refuses — radio buttons outside a `buttonsGroup` threw in the
 * constructor, and the dialog never opened — went unnoticed.
 */
class ExtractKeyDialogTest : PlatformBaseTest() {

    private fun build(existingKeys: List<String>) {
        addFileToProject("locales/en/account.json", """{"title": "Title"}""")
        addFileToProject("locales/fr/account.json", "{}")
        val file = myFixture.configureByText("App.jsx", "export const App = () => <p>Save</p>;")
        val model = ReadAction.compute<ExtractKeyModel, RuntimeException> {
            ExtractKeyModel.load(project, file, "Save", existingKeys, { argument, _ -> "{i18n.t($argument)}" })
        }
        val dialog = ExtractKeyDialog(project, model, file)
        try {
            Assertions.assertNotNull(dialog.preferredFocusedComponent)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    @Test
    fun opensWithoutAnExistingKey() = build(emptyList())

    @Test
    fun opensWithExistingKeys() = build(listOf("common:actions.save", "account:save"))
}
