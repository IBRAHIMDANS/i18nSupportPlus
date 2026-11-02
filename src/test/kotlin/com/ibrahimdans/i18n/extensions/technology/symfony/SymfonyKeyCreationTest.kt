package com.ibrahimdans.i18n.extensions.technology.symfony

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.quickfix.AllSourcesSelector
import com.ibrahimdans.i18n.plugin.ide.quickfix.CreateKeyQuickFix
import com.ibrahimdans.i18n.plugin.ide.waitForAsyncWork
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** *Create i18n key* in a Symfony catalogue writes the key where Symfony reads it, nested or written whole. */
class SymfonyKeyCreationTest : PlatformBaseTest() {

    private fun symfonyProject(catalogue: String) {
        myFixture.addFileToProject("composer.json", """{"require": {"symfony/translation": "7.1.*"}}""")
        myFixture.addFileToProject("translations/messages.en.yaml", catalogue)
        myFixture.configureByText("a.php", "<?php echo 1;")
    }

    private fun createKey(text: String, value: String) {
        val key = RawKeyParser(project).parse(RawKey(listOf(KeyElement.literal(text)), listOf("messages")))!!
        val previous = TestDialogManager.setTestInputDialog(answering(value))
        try {
            CreateKeyQuickFix(key, AllSourcesSelector(), "Create key", value).invoke(project, myFixture.editor)
            waitForAsyncWork()
        } finally {
            TestDialogManager.setTestInputDialog(previous)
        }
    }

    private fun catalogue(): String {
        FileDocumentManager.getInstance().saveAllDocuments()
        return myFixture.findFileInTempDir("translations/messages.en.yaml").let { String(it.contentsToByteArray()) }
    }

    @Test
    fun aKeySharingThePrefixOfKeysWrittenWholeIsWrittenWhole() {
        symfonyProject("flat.key: Flat\n")
        createKey("flat.other", "Other")
        assertEquals("flat.key: Flat\nflat.other: Other\n", catalogue())
    }

    @Test
    fun aKeyUnderANestedParentIsNested() {
        symfonyProject("user:\n  name: Name\n")
        createKey("user.email", "Email")
        assertEquals("user:\n  name: Name\n  email: Email\n", catalogue())
    }

    private fun answering(value: String) = object : TestInputDialog {
        override fun show(message: String): String = value
        override fun show(message: String, validator: InputValidator?): String = value
    }
}
