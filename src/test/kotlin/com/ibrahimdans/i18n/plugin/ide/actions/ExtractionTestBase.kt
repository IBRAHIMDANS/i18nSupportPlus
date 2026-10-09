package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager.setTestDialog
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import com.intellij.openapi.ui.TestInputDialog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

abstract class ExtractionTestBase: PlatformBaseTest() {

    protected val hint = "Extract i18n key"

    /** The keys the last extraction offered to reuse. */
    protected var offeredKeys: List<String> = emptyList()

    private val dialogOpener = ExtractI18nIntentionAction.opener

    @BeforeEach
    fun scriptTheExtractionDialog() {
        ExtractI18nIntentionAction.opener = { project, model, _ -> scriptedAnswer(project, model) }
    }

    @AfterEach
    fun restoreTheExtractionDialog() {
        ExtractI18nIntentionAction.opener = dialogOpener
    }

    /**
     * Stands in for the extraction dialog, which a headless container cannot show, answering it
     * through the test dialogs the cases already script: the [TestDialog] picks among the keys
     * offered (their index), *Create* (the next one) or cancels (any other); the
     * [TestInputDialog] types the key, its namespace selecting the files. The text goes to the
     * first file of the namespace — the reference locale — and the others are left empty.
     */
    private fun scriptedAnswer(project: Project, model: ExtractKeyModel): ExtractAnswer? {
        offeredKeys = model.existingKeys
        if (model.existingKeys.isNotEmpty()) {
            val buttons = (model.existingKeys + "Create" + "Cancel").toTypedArray()
            when (val index = Messages.showDialog(project, "", "", buttons, 0, null)) {
                in model.existingKeys.indices -> return ExtractAnswer.Reuse(model.existingKeys[index])
                model.existingKeys.size -> Unit
                else -> return null
            }
        }
        val typed = Messages.showInputDialog(project, "", "", null, null, null) ?: return null
        val namespace = DialogViewModel(project).parseKey(typed)?.ns?.text
        val key = if (namespace == null) typed else typed.substringAfter(Settings.getInstance(project).config().nsSeparator)
        val chosen = namespace ?: model.initialNamespace
        val sources = model.sources(chosen)
        return ExtractAnswer.Create(chosen, key, sources.associateWith { if (it == sources.first()) model.text else "" })
    }

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
        myFixture.launchActionAndWait(action)
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