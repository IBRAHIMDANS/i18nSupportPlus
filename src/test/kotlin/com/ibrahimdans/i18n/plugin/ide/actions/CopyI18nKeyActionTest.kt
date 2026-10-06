package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.ide.CopyPasteManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

class CopyI18nKeyActionTest : PlatformBaseTest() {

    /** Opens [content] at [path] — `<caret>` marks the caret — runs the action, returns the clipboard. */
    private fun copiedKey(path: String, content: String): String? {
        CopyPasteManager.getInstance().setContents(StringSelection(UNTOUCHED))
        val caret = content.indexOf(CARET)
        val file = myFixture.addFileToProject(path, content.replace(CARET, ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        val presentation = myFixture.testAction(CopyI18nKeyAction())
        assertTrue(presentation.isEnabledAndVisible, "the action must be offered in $path")
        return CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
    }

    @Test
    fun aNestedKeyIsCopiedWithItsNamespace() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        val key = copiedKey("locales/fr/common.json", """{"menu": {"ho<caret>me": "Accueil"}}""")
        assertEquals("common:menu.home", key)
    }

    @Test
    fun theCaretOnTheValueCopiesItsKey() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        val key = copiedKey("locales/fr/common.json", """{"menu": {"home": "Acc<caret>ueil"}}""")
        assertEquals("common:menu.home", key)
    }

    @Test
    fun aKeyOfTheDefaultNamespaceHasNoPrefix() = myFixture.runWithConfig(Config(defaultNs = "common")) {
        val key = copiedKey("locales/fr/common.json", """{"menu": {"ho<caret>me": "Accueil"}}""")
        assertEquals("menu.home", key)
    }

    @Test
    fun aLocaleNamedFileBelongsToTheDefaultNamespace() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        val key = copiedKey("locales/fr.json", """{"menu": {"ho<caret>me": "Accueil"}}""")
        assertEquals("menu.home", key)
    }

    @Test
    fun theConfiguredSeparatorsAreUsed() =
        myFixture.runWithConfig(Config(defaultNs = "translation", nsSeparator = "::", keySeparator = "/")) {
            val key = copiedKey("locales/fr/common.json", """{"menu": {"ho<caret>me": "Accueil"}}""")
            assertEquals("common::menu/home", key)
        }

    @Test
    fun aYamlKeyIsCopied() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        val key = copiedKey("locales/fr/common.yaml", "menu:\n  ho<caret>me: Accueil\n")
        assertEquals("common:menu.home", key)
    }

    @Test
    fun theActionIsHiddenOutsideATranslationFile() {
        myFixture.configureByText("package.json", """{"na<caret>me": "app"}""")
        CopyPasteManager.getInstance().setContents(StringSelection(UNTOUCHED))
        val presentation = myFixture.testAction(CopyI18nKeyAction())
        assertFalse(presentation.isVisible)
        assertEquals(UNTOUCHED, CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor))
    }

    @Test
    fun theActionIsDisabledWhereTheCaretIsOnNoKey() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        val file = myFixture.addFileToProject("locales/fr/common.json", """{"menu": {"home": "Accueil"}}""")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(0)
        val presentation = myFixture.testAction(CopyI18nKeyAction())
        assertTrue(presentation.isVisible)
        assertFalse(presentation.isEnabled)
    }

    private companion object {
        const val CARET = "<caret>"
        const val UNTOUCHED = "untouched"
    }
}
