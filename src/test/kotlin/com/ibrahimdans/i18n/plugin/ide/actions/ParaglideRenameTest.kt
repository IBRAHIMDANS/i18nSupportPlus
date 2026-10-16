package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Shift+F6 on Paraglide's `m.hello` renames the message in every locale and at every call site,
 * from the code or from the message file; the generated code is left as it is, Paraglide rewrites it.
 */
class ParaglideRenameTest : PlatformBaseTest() {

    @AfterEach
    fun resetDialogs() {
        TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
    }

    private fun text(path: String): String = ReadAction.compute<String, RuntimeException> {
        PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!.text
    }

    private fun addProject() {
        addFileToProject("messages/en.json", """{"hello": "Hello", "user": {"profile": {"title": "Profile"}}}""")
        addFileToProject("messages/fr.json", """{"hello": "Bonjour", "user": {"profile": {"title": "Profil"}}}""")
        addFileToProject("src/paraglide/runtime.js", "export const getLocale = () => 'en';")
        addFileToProject("src/paraglide/messages.js", "export * as m from './messages/_index.js'")
        addFileToProject("src/paraglide/messages/hello.js", "export const hello = () => 'Hello';")
        addFileToProject(
            "src/Other.ts",
            "import { m } from './paraglide/messages.js';\nexport const o = [m.hello(), m[\"user.profile.title\"]()];"
        )
    }

    private fun rename(newName: String) {
        TestDialogManager.setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = newName
            override fun show(message: String, validator: InputValidator?): String = newName
        })
        TestDialogManager.setTestDialog { Messages.YES }
        RenameI18nKeyHandler().invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)
    }

    private fun code(caretAt: String) {
        val code = "import { m } from './paraglide/messages.js';\nexport const a = $caretAt;"
        myFixture.configureFromExistingVirtualFile(addFileToProject("src/A.ts", code).virtualFile)
    }

    @Test
    fun fromTheCodeEveryLocaleAndCallSiteFollow() {
        addProject()
        code("m.hello()")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("hello") + 2)
        rename("greeting")

        assertTrue(myFixture.editor.document.text.contains("m.greeting()"), myFixture.editor.document.text)
        assertTrue(text("src/Other.ts").contains("m.greeting()"), text("src/Other.ts"))
        assertTrue(text("messages/en.json").contains("\"greeting\": \"Hello\""), text("messages/en.json"))
        assertTrue(text("messages/fr.json").contains("\"greeting\": \"Bonjour\""), text("messages/fr.json"))
        // Qualified: BasePlatformTestCase inherits JUnit 3's assertEquals(message, expected, actual), which would win here.
        Assertions.assertEquals("export const hello = () => 'Hello';", text("src/paraglide/messages/hello.js"), "the generated code is left to Paraglide")
    }

    /** A name that is no identifier switches the call sites to the index form. */
    @Test
    fun aNameThatIsNoIdentifierBecomesAnIndex() {
        addProject()
        code("m.hello()")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("hello") + 2)
        rename("say-hello")
        assertTrue(myFixture.editor.document.text.contains("m[\"say-hello\"]()"), myFixture.editor.document.text)
        assertTrue(text("messages/en.json").contains("\"say-hello\""), text("messages/en.json"))
    }

    /** The last segment of a nested key: the index keeps its other segments. */
    @Test
    fun aNestedKeyKeepsItsPath() {
        addProject()
        code("m[\"user.profile.title\"]()")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("title") + 2)
        rename("heading")
        assertTrue(myFixture.editor.document.text.contains("m[\"user.profile.heading\"]()"), myFixture.editor.document.text)
        assertTrue(text("src/Other.ts").contains("m[\"user.profile.heading\"]()"), text("src/Other.ts"))
        assertTrue(text("messages/fr.json").contains("\"heading\": \"Profil\""), text("messages/fr.json"))
    }

    /** From the message file, the call sites follow too, not only the property. */
    @Test
    fun fromTheMessageFileTheCallSitesFollow() {
        addProject()
        addFileToProject("project.inlang/settings.json", """{"baseLocale": "en", "locales": ["en", "fr"]}""")
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("messages/en.json"))
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("hello") + 2)
        rename("greeting")
        assertTrue(text("src/Other.ts").contains("m.greeting()"), text("src/Other.ts"))
        assertTrue(text("messages/fr.json").contains("\"greeting\""), text("messages/fr.json"))
    }

}
