package com.ibrahimdans.i18n.extensions.localization.php

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInsight.completion.CompletionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A package's keys `package::file.key` beyond their resolution ([PhpArrayLocalizationTest]): the
 * package's file offered while its name is typed, a package without override left alone, and the
 * override kept out of the locales of the tool window.
 */
class LaravelPackageKeysTest : PlatformBaseTest() {

    private val reported = listOf("annotator.unresolved.key", "annotator.unresolved.ns", "annotator.missing.default.ns")
        .map { PluginBundle.getMessage(it) }

    private fun laravelProjectWithPackage() {
        addFileToProject("composer.json", """{"require": {"php": "^8.2", "laravel/framework": "^11.0"}}""")
        addFileToProject("lang/en/messages.php", "<?php\n\nreturn ['welcome' => 'Welcome', 'auth' => ['failed' => 'Nope']];\n")
        addFileToProject("lang/vendor/courier/en/messages.php", "<?php\n\nreturn ['welcome' => 'Welcome from Courier', 'sent' => ['ok' => 'Sent']];\n")
    }

    // Each call writes a file of its own: a test may ask several times.
    private var calls = 0

    private fun offered(code: String): List<String> {
        myFixture.configureFromExistingVirtualFile(addFileToProject("resources/views/view${calls++}.php", code).virtualFile)
        myFixture.complete(CompletionType.BASIC, 1)
        // A single variant is inserted without a lookup: the key written is then the one offered.
        return myFixture.lookupElementStrings
            ?: listOfNotNull(Regex("""\('([^']*)'""").find(myFixture.file.text)?.groupValues?.get(1))
    }

    private fun reportedKeys(code: String): List<String> {
        myFixture.configureFromExistingVirtualFile(addFileToProject("resources/views/page${calls++}.php", code).virtualFile)
        return myFixture.doHighlighting().filter { it.description in reported }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset) }
    }

    @Test
    fun thePackageFileIsOfferedWhileThePackageNameIsTyped() {
        laravelProjectWithPackage()
        assertTrue("courier::messages." in offered("<?php echo __('cou<caret>');"), "by its package name")
        assertTrue("courier::messages." in offered("<?php echo __('courier::<caret>');"), "after the `::`")
        assertTrue("courier::messages." in offered("<?php echo trans('courier::mes<caret>');"), "by its file name")
    }

    @Test
    fun theFilesOfTheApplicationAreOfferedLikeThoseOfAPackage() {
        laravelProjectWithPackage()
        val atRoot = offered("<?php echo __('<caret>');")
        assertTrue(atRoot.containsAll(listOf("messages.", "courier::messages.")), "$atRoot")
        val byName = offered("<?php echo __('mes<caret>');")
        assertTrue("messages." in byName, "$byName")
    }

    @Test
    fun pastTheFilePartTheKeysOfTheFileAreOffered() {
        laravelProjectWithPackage()
        val offered = offered("<?php echo __('courier::messages.<caret>');")
        assertTrue(offered.containsAll(listOf("courier::messages.welcome", "courier::messages.sent")), "$offered")
        assertFalse("courier::messages." in offered, "$offered")
    }

    @Test
    fun pickingThePackageFileWritesItAndItsSeparator() {
        laravelProjectWithPackage()
        myFixture.configureFromExistingVirtualFile(
            addFileToProject("resources/views/pick.php", "<?php echo __('courier::<caret>');").virtualFile
        )
        myFixture.completeBasic()
        assertTrue("__('courier::messages.')" in myFixture.file.text, myFixture.file.text)
    }

    @Test
    fun noFileIsOfferedOutsideLaravel() {
        addFileToProject("lang/vendor/courier/en/messages.php", "<?php\n\nreturn ['welcome' => 'Welcome from Courier'];\n")
        assertFalse("courier::messages." in offered("<?php echo __('cou<caret>');"))
    }

    @Test
    fun aPackageKeyWithoutOverrideIsNotReported() {
        laravelProjectWithPackage()
        // The package ships its translations under Composer's vendor/ and registers them at runtime
        // (loadTranslationsFrom): without an override, there is no file to check the key against.
        assertEquals(emptyList<String>(), reportedKeys("<?php echo __('mailer::messages.welcome'); echo trans('mailer::auth.failed');"))
        // A package an override declares is still checked.
        assertEquals(listOf("zzMissing"), reportedKeys("<?php echo __('courier::messages.zzMissing');"))
    }

    @Test
    fun aPackageOverrideIsNoLocaleOfItsOwnInTheToolWindow() {
        laravelProjectWithPackage()
        addFileToProject("lang/fr/messages.php", "<?php\n\nreturn ['welcome' => 'Bienvenue'];\n")
        addFileToProject("lang/vendor/courier/fr/messages.php", "<?php\n\nreturn ['welcome' => 'Bienvenue de Courier'];\n")
        assertEquals(listOf("en", "fr"), TranslationDataLoader.discoverLocales(project))
    }
}
