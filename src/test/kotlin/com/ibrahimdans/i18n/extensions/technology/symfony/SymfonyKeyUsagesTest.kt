package com.ibrahimdans.i18n.extensions.technology.symfony

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.RenameI18nKeyHandler
import com.ibrahimdans.i18n.plugin.ide.inspection.UnusedTranslationKeyInspection
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiPolyVariantReference
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A Symfony key is used where a PHP call or a Twig template names it: *Unused translation key*, the
 * navigation from a catalogue and Shift+F6 see both, in YAML and XLIFF catalogues alike.
 */
class SymfonyKeyUsagesTest : PlatformBaseTest() {

    private val unusedMessage = "No reference to this key found in code"

    @AfterEach
    fun resetDialogs() {
        TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
    }

    private fun symfonyProject() {
        myFixture.addFileToProject("composer.json", """{"require": {"symfony/translation": "7.1.*"}}""")
        myFixture.addFileToProject(
            "translations/messages.en.yaml",
            "home:\n  title: Welcome\n  intro: Intro\n  unused: Nothing\nfooter: Footer\nflat.key: Flat\n"
        )
        myFixture.addFileToProject(
            "translations/validators.en.xlf",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <xliff xmlns="urn:oasis:names:tc:xliff:document:1.2" version="1.2">
              <file source-language="en" target-language="en" datatype="plaintext" original="file.ext">
                <body>
                  <trans-unit id="a1" resname="contact.email.invalid">
                    <source>contact.email.invalid</source>
                    <target>Invalid e-mail</target>
                  </trans-unit>
                </body>
              </file>
            </xliff>
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "templates/home.html.twig",
            "<h1>{{ 'home.title'|trans }}</h1>\n<p>{% trans %}footer{% endtrans %}</p>\n<p>{{ 'flat.key'|trans }}</p>\n" +
                "<p>{{ 'contact.email.invalid'|trans({}, 'validators') }}</p>"
        )
        myFixture.addFileToProject("src/Controller/Home.php", "<?php \$translator->trans('home.intro');")
    }

    private fun answering(value: String) = TestDialogManager.setTestInputDialog(object : TestInputDialog {
        override fun show(message: String): String = value
        override fun show(message: String, validator: InputValidator?): String = value
    })

    private fun documentText(path: String): String =
        FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(path))!!.text

    private fun renameAt(path: String, code: String, newName: String) {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, code).virtualFile)
        answering(newName)
        RenameI18nKeyHandler().invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)
    }

    @Test
    fun keysUsedInTwigOrPhpAreUsed() {
        symfonyProject()
        myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("translations/messages.en.yaml"))
        val text = myFixture.editor.document.text
        val unused = myFixture.doHighlighting().filter { it.description == unusedMessage }
            .map { text.substring(it.startOffset, it.endOffset).trim('"', '\'') }
        assertEquals(setOf("unused"), unused.toSet())
    }

    @Test
    fun anXliffResnameLeadsToTheCodeUsingIt() {
        symfonyProject()
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("translations/validators.en.xlf"))
        val offset = myFixture.editor.document.text.indexOf("contact.email.invalid\">") + 3
        val reference = myFixture.file.findReferenceAt(offset) as? PsiPolyVariantReference
        val usages = reference?.multiResolve(false)?.mapNotNull { it.element?.containingFile?.name }.orEmpty()
        assertEquals(listOf("home.html.twig"), usages.distinct())
    }

    @Test
    fun aYamlKeyLeadsToTheCodeUsingItInItsDomain() {
        symfonyProject()
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("translations/messages.en.yaml"))
        val offset = myFixture.editor.document.text.indexOf("intro:") + 1
        val reference = myFixture.file.findReferenceAt(offset) as? PsiPolyVariantReference
        val usages = reference?.multiResolve(false)?.mapNotNull { it.element?.containingFile?.name }.orEmpty()
        assertEquals(listOf("Home.php"), usages.distinct())
    }

    @Test
    fun renamingFromPhpRenamesTheCatalogueAndTheTemplates() {
        symfonyProject()
        renameAt("src/a.php", "<?php \$translator->trans('home.ti<caret>tle');", "heading")
        assertTrue("trans('home.heading')" in myFixture.editor.document.text, myFixture.editor.document.text)
        assertTrue("  heading: Welcome" in documentText("translations/messages.en.yaml"), documentText("translations/messages.en.yaml"))
        assertTrue("'home.heading'|trans" in documentText("templates/home.html.twig"), documentText("templates/home.html.twig"))
    }

    @Test
    fun renamingAKeyWrittenWholeRenamesItWhole() {
        symfonyProject()
        renameAt("templates/b.html.twig", "{{ 'flat.k<caret>ey'|trans }}", "other")
        assertTrue("'flat.other'|trans" in myFixture.editor.document.text, myFixture.editor.document.text)
        assertTrue("flat.other: Flat" in documentText("translations/messages.en.yaml"), documentText("translations/messages.en.yaml"))
        assertTrue("'flat.other'|trans" in documentText("templates/home.html.twig"), documentText("templates/home.html.twig"))
    }

    @Test
    fun renamingAnXliffKeyRewritesItsResnameAndItsSource() {
        symfonyProject()
        renameAt("src/b.php", "<?php \$translator->trans('contact.email.inva<caret>lid', [], 'validators');", "wrong")
        val xliff = documentText("translations/validators.en.xlf")
        assertTrue("resname=\"contact.email.wrong\"" in xliff && "<source>contact.email.wrong</source>" in xliff, xliff)
        assertTrue("'contact.email.wrong'|trans" in documentText("templates/home.html.twig"), documentText("templates/home.html.twig"))
    }
}
