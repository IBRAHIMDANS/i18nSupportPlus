package com.ibrahimdans.i18n.extensions.localization.php

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.quickfix.AllSourcesSelector
import com.ibrahimdans.i18n.plugin.ide.quickfix.CreateKeyQuickFix
import com.ibrahimdans.i18n.plugin.ide.waitForAsyncWork
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Laravel's `lang/<locale>/<file>.php` translation files, read, navigated, completed and written. */
class PhpArrayLocalizationTest : PlatformBaseTest() {

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")

    private val messages = """
        <?php

        return [
            'welcome' => 'Welcome',
            'auth' => [
                'failed' => 'These credentials do not match.',
            ],
        ];
    """.trimIndent()

    private fun laravelProject(messagesContent: String = messages) {
        addFileToProject("composer.json", """{"require": {"php": "^8.2", "laravel/framework": "^11.0"}}""")
        addFileToProject("lang/en/messages.php", messagesContent)
    }

    private fun resolvedValue(code: String): String? {
        myFixture.configureFromExistingVirtualFile(addFileToProject("app/Http/Controllers/HomeController.php", code).virtualFile)
        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent ?: return null
        return element.references.firstOrNull()?.resolve()?.text?.unQuote()
    }

    private fun unresolvedKeys(code: String): List<String> {
        myFixture.configureFromExistingVirtualFile(addFileToProject("resources/views/home.php", code).virtualFile)
        return myFixture.doHighlighting().filter { it.description == unresolved }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset) }.distinct()
    }

    private fun documentText(path: String): String =
        FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(path))!!.text

    @Test
    fun aKeyResolvesInTheFileNamedByItsFirstSegment() {
        laravelProject()
        assertEquals("Welcome", resolvedValue("<?php echo __('messages.wel<caret>come');"))
    }

    @Test
    fun aNestedArrayIsANestedKey() {
        laravelProject()
        assertEquals("These credentials do not match.", resolvedValue("<?php echo trans('messages.auth.fai<caret>led');"))
    }

    @Test
    fun theLongArraySyntaxIsReadToo() {
        laravelProject("<?php\n\nreturn array(\n    'welcome' => 'Hello',\n    'auth' => array('failed' => 'Nope'),\n);\n")
        assertEquals("Hello", resolvedValue("<?php echo __('messages.wel<caret>come');"))
        assertTrue(unresolvedKeys("<?php echo __('messages.auth.failed'); echo __('messages.zzMissing');") == listOf("zzMissing"))
    }

    @Test
    fun anUnknownKeyIsReported() {
        laravelProject()
        assertEquals(
            listOf("zzMissing"),
            unresolvedKeys("<?php echo __('messages.welcome'); echo __('messages.auth.failed'); echo __('messages.auth.zzMissing');")
        )
    }

    @Test
    fun theKeysOfTheFileAreCompleted() {
        laravelProject()
        myFixture.configureFromExistingVirtualFile(
            addFileToProject("resources/views/form.php", "<?php echo __('messages.<caret>');").virtualFile
        )
        myFixture.complete(CompletionType.BASIC, 1)
        val offered = myFixture.lookupElementStrings.orEmpty()
        // Offered as written, the file name first: the lookup replaces the whole key.
        assertTrue(offered.containsAll(listOf("messages.welcome", "messages.auth")), "the file's keys must be offered: $offered")

        myFixture.configureFromExistingVirtualFile(
            addFileToProject("resources/views/single.php", "<?php echo __('messages.wel<caret>');").virtualFile
        )
        myFixture.completeBasic()
        assertTrue("__('messages.welcome')" in myFixture.file.text, myFixture.file.text)
    }

    @Test
    fun aKeyOfTheFileLeadsToTheCodeUsingIt() {
        laravelProject()
        addFileToProject("resources/views/welcome.php", "<?php echo __('messages.welcome');")
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("lang/en/messages.php"))
        val offset = myFixture.file.text.indexOf("'welcome'") + 2
        val element = myFixture.file.findElementAt(offset)?.parent
        assertNotNull(element)
        val references = element!!.references
        assertEquals(1, references.size, "the key must be searched for in the code")
        assertEquals("messages.welcome", references[0].resolve()?.text?.unQuote())
    }

    @Test
    fun aMissingKeyIsWrittenIntoTheArrayOfEveryLocale() {
        laravelProject()
        addFileToProject("lang/fr/messages.php", "<?php\n\nreturn array(\n    'welcome' => 'Bienvenue'\n);\n")
        myFixture.configureFromExistingVirtualFile(addFileToProject("resources/views/a.php", "<?php echo 1;").virtualFile)
        createKey("messages.greeting", "Hello 'you'")
        createKey("messages.auth.throttle", "Too many attempts")

        val english = documentText("lang/en/messages.php")
        assertTrue("'greeting' => 'Hello \\'you\\''," in english, english)
        assertTrue(Regex("""'failed' => 'These credentials do not match\.',\s*'throttle' => 'Too many attempts'""").containsMatchIn(english), english)
        val french = documentText("lang/fr/messages.php")
        assertTrue(Regex("""'welcome' => 'Bienvenue',\s*'greeting' => 'Hello \\'you\\''""").containsMatchIn(french), french)
        assertTrue(Regex("""'auth' => \[\s*'throttle' => 'Too many attempts',\s*]""").containsMatchIn(french), french)
        assertEquals(emptyList<String>(), unresolvedKeys("<?php echo __('messages.greeting'); echo __('messages.auth.throttle');"))
    }

    @Test
    fun aPhpFileOutsideALocaleDirectoryIsNoTranslationFile() {
        addFileToProject("composer.json", """{"require": {"laravel/framework": "^11.0"}}""")
        addFileToProject("config/messages.php", messages)
        assertNull(resolvedValue("<?php echo __('messages.wel<caret>come');"))

        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("config/messages.php"))
        val element = myFixture.file.findElementAt(myFixture.file.text.indexOf("'welcome'") + 2)?.parent
        assertNotNull(element)
        assertTrue(element!!.references.isEmpty(), "a configuration array leads nowhere")

        // A locale-named file outside a locale directory is read by the scan of all sources, not as a translation file.
        val localeNamed = addFileToProject("app/en.php", messages)
        assertNull(PhpArrayLocalization().elementsTree(localeNamed))
        assertNotNull(PhpArrayLocalization().elementsTree(addFileToProject("lang/en/messages.php", messages)))
    }

    @Test
    fun aPackageOverrideIsNoFileOfTheApplication() {
        val app = addFileToProject("lang/en/messages.php", messages).virtualFile
        val vendor = addFileToProject("lang/vendor/courier/en/messages.php", messages).virtualFile
        val code = addFileToProject("app/Models/User.php", "<?php class User {}").virtualFile
        assertTrue(PhpArrayLocalization.isInLocaleDirectory(app))
        assertFalse(PhpArrayLocalization.isInLocaleDirectory(vendor))
        assertFalse(PhpArrayLocalization.isInLocaleDirectory(code))
    }

    @Test
    fun aNewFileReturnsTheNestedKeys() {
        val parser = RawKeyParser(project)
        laravelProject()
        val key = parser.parse(RawKey(listOf(KeyElement.literal("messages.a.b"))))!!
        assertEquals(
            "<?php\n\nreturn [\n    'a' => [\n        'b' => 'Value',\n    ],\n];\n",
            PhpArrayContentGenerator().generateContent(key.compositeKey, "Value")
        )
    }

    private fun createKey(text: String, value: String) {
        val key = RawKeyParser(project).parse(RawKey(listOf(KeyElement.literal(text))))!!
        assertEquals("messages", key.ns?.text)
        val previous = TestDialogManager.setTestInputDialog(answering(value))
        try {
            CreateKeyQuickFix(key, AllSourcesSelector(), "Create key", value).invoke(project, myFixture.editor)
            waitForAsyncWork()
        } finally {
            TestDialogManager.setTestInputDialog(previous)
        }
    }

    private fun answering(value: String) = object : TestInputDialog {
        override fun show(message: String): String = value
        override fun show(message: String, validator: InputValidator?): String = value
    }
}
