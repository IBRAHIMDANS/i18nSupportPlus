package com.ibrahimdans.i18n.plugin.rules

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.unQuote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The *Key assistance rules* reach the editor: an including rule makes a call no framework
 * publishes a translation call, an excluding one takes a published call out.
 */
class KeyRulesWiringTest : PlatformBaseTest() {

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")

    private fun config(vararg rules: EditorRuleState) = Config(rules = rules.toList())

    private fun addTranslations() {
        myFixture.addFileToProject("assets/test.json", """{"ref": {"key": "Value"}}""")
    }

    private fun reportsUnresolved(path: String, code: String): Boolean {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, code).virtualFile)
        return myFixture.doHighlighting().mapNotNull { it.description }.contains(unresolved)
    }

    private fun resolvedValue(fileName: String, code: String): String? {
        myFixture.configureByText(fileName, code)
        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent ?: return null
        return element.references.firstOrNull()?.resolve()?.text?.unQuote()
    }

    @Test
    fun anIncludingRuleMakesAnUnpublishedCallATranslationCall() {
        addTranslations()
        assertFalse(reportsUnresolved("src/Plain.js", "translate('test:ref.missing')"), "no rule: translate is not a translation call")

        myFixture.runWithConfig(config(EditorRuleState(trigger = "translate"))) {
            assertTrue(reportsUnresolved("src/Ruled.js", "translate('test:ref.missing')"))
            assertEquals("Value", resolvedValue("Nav.js", "translate('test:ref.ke<caret>y')"))
        }
    }

    @Test
    fun anExcludingRuleOnAPathTakesAPublishedCallOut() = myFixture.runWithConfig(
        config(EditorRuleState(trigger = "t", exclude = true, constraintType = "filePath", matchMode = "regex", value = "legacy/"))
    ) {
        addTranslations()
        assertFalse(reportsUnresolved("legacy/Old.js", "t('test:ref.missing')"), "t is excluded under legacy/")
        assertTrue(reportsUnresolved("src/New.js", "t('test:ref.missing')"), "t is still a translation call elsewhere")
    }

    @Test
    fun anImportConstraintHoldsOnlyInFilesImportingThePackage() = myFixture.runWithConfig(
        config(EditorRuleState(trigger = "translate", constraintType = "import", matchMode = "prefix", value = "my-i18n"))
    ) {
        addTranslations()
        assertTrue(reportsUnresolved("src/A.js", "import { translate } from 'my-i18n/core';\ntranslate('test:ref.missing')"))
        assertFalse(reportsUnresolved("src/B.js", "import { translate } from 'other';\ntranslate('test:ref.missing')"))
    }

    @Test
    fun anIncludingRuleForPhpResolvesDoubleUnderscore() = myFixture.runWithConfig(
        config(EditorRuleState(language = "php", trigger = "__"))
    ) {
        addTranslations()
        assertEquals("Value", resolvedValue("view.php", "<?php echo __('test:ref.ke<caret>y');"))
    }
}
