package com.ibrahimdans.i18n.plugin.ide.references.code

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ctrl+click on Paraglide's `m.hello` opens the message in the preview locale, not the JavaScript
 * function Paraglide generates; a key no file holds is left to the JavaScript navigation.
 */
class ParaglideGotoDeclarationHandlerTest : PlatformBaseTest() {

    private fun addMessages() {
        if (myFixture.tempDirFixture.getFile("messages/en.json") != null) return
        addFileToProject("messages/en.json", """{"hello": "Hello", "user": {"profile": {"title": "Profile"}}, "only_en": "English"}""")
        addFileToProject("messages/fr.json", """{"hello": "Bonjour", "user": {"profile": {"title": "Profil"}}}""")
    }

    /** Where Ctrl+click at `<caret>` in [code] goes: each target as `file: text`, or null for the platform's default. */
    private fun targets(code: String, fileName: String = "A.ts"): List<String>? {
        addMessages()
        myFixture.configureByText(fileName, "import { m } from './paraglide/messages.js';\n$code")
        return ReadAction.compute<List<String>?, RuntimeException> {
            val leaf = myFixture.file.findElementAt(myFixture.caretOffset - 1)
            ParaglideGotoDeclarationHandler().getGotoDeclarationTargets(leaf, myFixture.caretOffset, myFixture.editor)
                ?.map { "${it.containingFile.parent?.name}/${it.containingFile.name}: ${it.text.unQuote()}" }
        }
    }

    @Test
    fun registeredFirst() {
        assertTrue(GotoDeclarationHandler.EP_NAME.extensionList.first { it is ParaglideGotoDeclarationHandler || it is I18nGotoDeclarationHandler } is ParaglideGotoDeclarationHandler)
    }

    @Test
    fun opensTheMessageInThePreviewLocale() = myFixture.runWithConfig(Config(previewLocale = "fr")) {
        assertEquals(listOf("messages/fr.json: Bonjour"), targets("export const a = m.hel<caret>lo();"))
    }

    @Test
    fun anIndexedMemberOpensTheNestedMessage() = myFixture.runWithConfig(Config(previewLocale = "en")) {
        assertEquals(listOf("messages/en.json: Profile"), targets("export const a = m[\"user.profile.ti<caret>tle\"]();"))
    }

    /** No file holds it: null, so the platform's JavaScript navigation applies. */
    @Test
    fun anUnknownKeyLeavesTheJavaScriptNavigation() = myFixture.runWithConfig(Config(previewLocale = "en")) {
        assertNull(targets("export const a = m.miss<caret>ing();"))
    }

    /** Missing from the preview locale: the locales holding it are offered, not the generated file. */
    @Test
    fun aKeyMissingFromThePreviewLocaleOffersTheOthers() = myFixture.runWithConfig(Config(previewLocale = "fr")) {
        assertEquals(listOf("messages/en.json: English"), targets("export const a = m.only<caret>_en();"))
    }

    @Test
    fun anMOfAnotherModuleIsNotFollowed() = myFixture.runWithConfig(Config(previewLocale = "en")) {
        addMessages()
        myFixture.configureByText("B.ts", "import { m } from './math';\nexport const a = m.hel<caret>lo();")
        val target = ReadAction.compute<Array<*>?, RuntimeException> {
            ParaglideGotoDeclarationHandler().getGotoDeclarationTargets(
                myFixture.file.findElementAt(myFixture.caretOffset - 1), myFixture.caretOffset, myFixture.editor
            )
        }
        assertNull(target)
    }

    @Test
    fun aModuleOfAnotherFrameworkIsNotFollowed() = myFixture.runWithConfig(
        Config(previewLocale = "en", modules = listOf(ModuleConfig(name = "web", rootDirectory = "apps/web", preset = "i18next")))
    ) {
        addMessages()
        val file = myFixture.addFileToProject("apps/web/A.ts", "import { m } from './paraglide/messages.js';\nexport const a = m.hello();")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val offset = myFixture.editor.document.text.indexOf("hello") + 2
        val target = ReadAction.compute<Array<*>?, RuntimeException> {
            ParaglideGotoDeclarationHandler().getGotoDeclarationTargets(myFixture.file.findElementAt(offset), offset, myFixture.editor)
        }
        assertNull(target)
    }
}
