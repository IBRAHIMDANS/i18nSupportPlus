package com.ibrahimdans.i18n.extensions.technology.angulari18n

import com.ibrahimdans.i18n.extensions.lang.js.AngularTemplateTextExtractor
import com.ibrahimdans.i18n.extensions.lang.js.HardcodedAngularTextInspection
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractionTestBase
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Angular's own i18n: a template text holding interpolations is marked `i18n="@@id"` as it is, and its unit
 * receives the placeholders `ng extract-i18n` writes for the same text. A text Angular would read otherwise
 * than the template writes it is still left to `ng extract-i18n`.
 */
class AngularNativeInterpolationExtractionTest : ExtractionTestBase() {

    private companion object {
        const val ANGULAR_JSON = """{ "version": 1, "projects": { "shop": { "root": "", "sourceRoot": "src",
  "i18n": { "sourceLocale": "en-US", "locales": { "fr": "src/locale/messages.fr.xlf" } } } } }"""

        const val SOURCE = """<?xml version="1.0" encoding="UTF-8" ?>
<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
  <file source-language="en-US" datatype="plaintext" original="ng2.template">
    <body>
      <trans-unit id="home.title" datatype="html">
        <source>Welcome</source>
      </trans-unit>
    </body>
  </file>
</xliff>
"""

        const val FRENCH = """<?xml version="1.0" encoding="UTF-8" ?>
<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
  <file source-language="en-US" target-language="fr" datatype="plaintext" original="ng2.template">
    <body>
      <trans-unit id="home.title" datatype="html">
        <source>Welcome</source>
        <target>Bienvenue</target>
      </trans-unit>
    </body>
  </file>
</xliff>
"""

        const val SOURCE_2 = """<?xml version="1.0" encoding="UTF-8" ?>
<xliff version="2.0" xmlns="urn:oasis:names:tc:xliff:document:2.0" srcLang="en-US">
  <file id="ngi18n" original="ng.template">
    <unit id="home.title">
      <segment>
        <source>Welcome</source>
      </segment>
    </unit>
  </file>
</xliff>
"""
    }

    @AfterEach
    fun restoreInputDialog() {
        setTestInputDialog(TestInputDialog.DEFAULT)
    }

    private fun addWorkspace(source: String = SOURCE) {
        addFileToProject("angular.json", ANGULAR_JSON)
        addFileToProject("src/locale/messages.xlf", source)
        addFileToProject("src/locale/messages.fr.xlf", FRENCH)
    }

    /** A template parsed as Angular: a component points at it, `@angular/core` resolvable. */
    private fun openTemplate(template: String) {
        addFileToProject("package.json", """{"dependencies":{"@angular/core":"17.0.0","@angular/localize":"17.0.0"}}""")
        addFileToProject("node_modules/@angular/core/package.json", """{"name":"@angular/core","version":"17.0.0"}""")
        addFileToProject(
            "src/app/app.component.ts",
            "import { Component } from '@angular/core';\n" +
                "@Component({ selector: 'app-root', templateUrl: './app.component.html' })\nexport class AppComponent {}"
        )
        myFixture.configureFromExistingVirtualFile(addFileToProject("src/app/app.component.html", template).virtualFile)
        Assertions.assertTrue(myFixture.file.fileType.name.startsWith("Angular"), myFixture.file.fileType.name)
    }

    private fun textOf(path: String): String {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(path))!!.text
    }

    private fun extract(template: String, id: String, source: String = SOURCE) = myFixture.runWithConfig(Config()) {
        addWorkspace(source)
        myFixture.enableInspections(HardcodedAngularTextInspection::class.java)
        openTemplate(template)
        val fix = myFixture.getAllQuickFixes().single { it.text == hint }
        setTestInputDialog(predefinedTextInputDialog(id))
        myFixture.launchActionAndWait(fix)
    }

    /** Why the text at the caret cannot be extracted, null when it can. */
    private fun refusalAtCaret(template: String): String? {
        var refusal: String? = null
        myFixture.runWithConfig(Config()) {
            addWorkspace()
            openTemplate(template)
            refusal = ReadAction.compute<String?, RuntimeException> {
                val text = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), XmlText::class.java, false)!!
                AngularTemplateTextExtractor().refusal(text)
            }
        }
        return refusal
    }

    @Test
    fun aTextWithAnInterpolationIsMarkedAsItIsAndItsUnitHoldsThePlaceholder() {
        extract("<p>Hel<caret>lo {{ user.name }}!</p>", "home.greeting")

        myFixture.checkResult("<p i18n=\"@@home.greeting\">Hello {{ user.name }}!</p>")
        val source = textOf("src/locale/messages.xlf")
        Assertions.assertTrue(
            source.contains("""<source>Hello <x id="INTERPOLATION" equiv-text="{{ user.name }}"/>!</source>"""),
            source,
        )
    }

    @Test
    fun twoInterpolationsAreNamedAsNgExtractI18nNamesThem() {
        extract("<span>{{ count }} items for {{name}}, sh<caret>own to {{ count }}</span>", "cart.count")

        myFixture.checkResult("<span i18n=\"@@cart.count\">{{ count }} items for {{name}}, shown to {{ count }}</span>")
        val source = textOf("src/locale/messages.xlf")
        Assertions.assertTrue(
            source.contains(
                """<source><x id="INTERPOLATION" equiv-text="{{ count }}"/> items for <x id="INTERPOLATION_1" equiv-text="{{name}}"/>, """ +
                    """shown to <x id="INTERPOLATION" equiv-text="{{ count }}"/></source>"""
            ),
            source,
        )
    }

    @Test
    fun anXliff2ReferenceReceivesPhElements() {
        extract("<p>Hel<caret>lo {{ user.name }}, {{ count }} new</p>", "home.greeting", SOURCE_2)

        val source = textOf("src/locale/messages.xlf")
        Assertions.assertTrue(
            source.contains(
                """<source>Hello <ph id="0" equiv="INTERPOLATION" disp="{{ user.name }}"/>, <ph id="1" equiv="INTERPOLATION_1" disp="{{ count }}"/> new</source>"""
            ),
            source,
        )
    }

    @Test
    fun aTextWithAnInterpolationAndNoTagIsWrappedAsItIs() {
        extract("@if (ready) { Hel<caret>lo {{ name }} }", "home.hello")

        myFixture.checkResult("@if (ready) { <ng-container i18n=\"@@home.hello\">Hello {{ name }}</ng-container> }")
        Assertions.assertTrue(textOf("src/locale/messages.xlf").contains("""<source>Hello <x id="INTERPOLATION" equiv-text="{{ name }}"/></source>"""))
    }

    @Test
    fun aTextOnOneLineFillingItsTagIsNotRefused() {
        Assertions.assertNull(refusalAtCaret("<p>Hel<caret>lo {{ user.name }}!</p>"))
    }

    /** Angular collapses the line break and keeps the blanks around the text: `ng extract-i18n` writes that message. */
    @Test
    fun aTextSpreadOverLinesIsLeftToNgExtractI18n() {
        Assertions.assertNotNull(refusalAtCaret("<p>\n  Hel<caret>lo {{ user.name }}!\n</p>"))
    }

    @Test
    fun aTextWithTwoBlanksIsLeftToNgExtractI18n() {
        Assertions.assertNotNull(refusalAtCaret("<p>Hel<caret>lo  {{ user.name }}!</p>"))
    }
}
