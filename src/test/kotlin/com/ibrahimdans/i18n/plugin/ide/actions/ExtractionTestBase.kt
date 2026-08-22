package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager.setTestDialog
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import com.intellij.openapi.ui.TestInputDialog
import org.junit.jupiter.api.Assertions.*

abstract class ExtractionTestBase: PlatformBaseTest() {

    protected val hint = "Extract i18n key"

    override fun getTestDataPath(): String = "src/test/resources/keyExtraction"

    protected fun config(ext: String, extractSorted: Boolean = false) =
            Config(preferredLocalization = if(ext == "yml") "yaml" else "json", extractSorted = extractSorted)

    /**
     * Runs one extraction case: the intention is found, launched, and both the source file and
     * the translation file are checked against their expected content.
     *
     * Every path through this method ends on those two assertions. It used to open with a
     * `if (!isReadAccessAllowed()) return`, which made the whole case pass without checking
     * anything whenever it fired — the assertions are the only reason this helper exists.
     * `PlatformBaseTest` dispatches each test onto the EDT, where read access is always held,
     * so the guard could never fire; replacing it with a `fail()` left all 86 cases green.
     */
    protected fun runTestCase(
            srcName: String,
            src: String,
            patched: String,
            translationName: String,
            origTranslation: String,
            patchedTranslation: String,
            inputDialog: TestInputDialog,
            message: TestDialog? = null) {
        myFixture.configureByText(srcName, src)
        myFixture.addFileToProject(translationName, origTranslation)
        val action = myFixture.findSingleIntention(hint)
        assertNotNull(action)
        setTestInputDialog(inputDialog)
        if (message != null) setTestDialog(message)
        myFixture.launchAction(action)
        myFixture.checkResult(patched)
        myFixture.checkResult(translationName, patchedTranslation, false)
    }

    protected fun predefinedTextInputDialog(newKey: String): TestInputDialog {
        var callCount = 0
        return object : TestInputDialog {
            override fun show(message: String): String? = null
            override fun show(message: String, validator: InputValidator?): String? {
                callCount++
                // First call: key input dialog (returns the i18n key)
                // Second call: translation value dialog (returns null → fallback to source text)
                return if (callCount == 1) newKey else null
            }
        }
    }
}