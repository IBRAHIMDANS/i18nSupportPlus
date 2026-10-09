package com.ibrahimdans.i18n.extensions.lang.php

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

/** `__()`, `trans()` and `trans_choice()` are translation calls in a Laravel project, and only there. */
class LaravelCallsTest : PlatformBaseTest() {

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("assets/test.json", """{"ref": {"key": "Value"}}""")
    }

    private fun laravelProject() {
        myFixture.addFileToProject("composer.json", """{"require": {"php": "^8.2", "laravel/framework": "^11.0"}}""")
    }

    private fun resolvedValue(fileName: String, code: String): String? {
        myFixture.configureByText(fileName, code)
        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent ?: return null
        return element.references.firstOrNull()?.resolve()?.text?.unQuote()
    }

    private fun reportsUnresolved(path: String, code: String): Boolean {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, code).virtualFile)
        return myFixture.doHighlighting().mapNotNull { it.description }.contains(unresolved)
    }

    @Test
    fun theThreeHelpersResolveAKeyInALaravelProject() {
        laravelProject()
        assertEquals("Value", resolvedValue("a.php", "<?php echo __('test:ref.ke<caret>y');"))
        assertEquals("Value", resolvedValue("b.php", "<?php echo trans('test:ref.ke<caret>y');"))
        assertEquals("Value", resolvedValue("c.php", "<?php echo trans_choice('test:ref.ke<caret>y', 2);"))
        assertTrue(reportsUnresolved("src/view.php", "<?php echo trans('test:ref.missing');"))
    }

    @Test
    fun withoutLaravelTheHelpersHoldText() {
        // WordPress: `__('Hello', 'domain')` holds a text, not a key.
        assertFalse(reportsUnresolved("src/plugin.php", "<?php echo __('test:ref.missing', 'domain');"))
    }

    @Test
    fun aComposerManifestUnderVendorDoesNotMakeALaravelProject() {
        myFixture.addFileToProject("vendor/acme/lib/composer.json", """{"require": {"illuminate/translation": "^11.0"}}""")
        assertFalse(reportsUnresolved("src/plugin.php", "<?php echo trans('test:ref.missing');"))
    }

    @Test
    fun getTextModeKeepsItsOwnAliases() = myFixture.runWithConfig(Config(gettext = true)) {
        laravelProject()
        assertFalse(reportsUnresolved("src/view.php", "<?php echo trans('test:ref.missing');"), "trans is no GetText alias")
    }

    @Test
    fun anExcludingRuleTakesTransOut() = myFixture.runWithConfig(
        Config(rules = listOf(EditorRuleState(language = "php", trigger = "trans", exclude = true)))
    ) {
        laravelProject()
        assertFalse(reportsUnresolved("src/a.php", "<?php echo trans('test:ref.missing');"))
        assertTrue(reportsUnresolved("src/b.php", "<?php echo __('test:ref.missing');"))
    }

    @Test
    fun extractingInALaravelProjectWritesDoubleUnderscore() {
        laravelProject()
        myFixture.configureByText("view.php", "<?php echo 'Hel<caret>lo';")
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        assertEquals("__('welcome')", PhpTranslationExtractor().template(element)("'welcome'"))
    }
}
