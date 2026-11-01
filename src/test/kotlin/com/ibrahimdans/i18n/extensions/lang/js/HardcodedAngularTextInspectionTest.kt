package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.ide.actions.ExtractionTestBase
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Hard-coded text in an Angular template is reported — and nothing else is — and its quick fix
 * writes the pipe of the project's library.
 */
class HardcodedAngularTextInspectionTest : ExtractionTestBase() {

    private val message = "Hard-coded text: extract it to a translation key"

    private fun angularProject(library: String = "\"@ngx-translate/core\": \"15.0.0\"") {
        addFileToProject("package.json", """{"dependencies": {"@angular/core": "17.0.0", $library}}""")
        addFileToProject("node_modules/@angular/core/package.json", """{"name":"@angular/core","version":"17.0.0"}""")
        addFileToProject(
            "src/app/app.component.ts",
            "import { Component } from '@angular/core';\n" +
                "@Component({ selector: 'app-root', templateUrl: './tpl.html' })\nexport class AppComponent {}"
        )
    }

    private fun openTemplate(template: String, library: String = "\"@ngx-translate/core\": \"15.0.0\"") {
        angularProject(library)
        myFixture.enableInspections(HardcodedAngularTextInspection::class.java)
        myFixture.configureFromExistingVirtualFile(addFileToProject("src/app/tpl.html", template).virtualFile)
        Assertions.assertEquals("Angular17Html", myFixture.file.fileType.name, "the template must be parsed as an Angular template")
    }

    /** The texts reported in [template]. */
    private fun reportedIn(template: String): List<String> {
        var reported = emptyList<String>()
        myFixture.runWithConfig(config("json")) {
            openTemplate(template)
            reported = myFixture.doHighlighting()
                .filter { it.severity == HighlightSeverity.WEAK_WARNING && it.description == message }
                .map { it.text }
        }
        return reported
    }

    @Test
    fun textAndVisibleAttributesAreReported() {
        Assertions.assertEquals(
            listOf("Save", "Name", "Logo"),
            reportedIn("""<button>Save</button><input placeholder="Name"><img alt="Logo" src="a.png">""")
        )
    }

    @Test
    fun aTextAroundAnInterpolationIsReportedOnceOverTheWholeMessage() {
        Assertions.assertEquals(
            listOf("Hello {{ user.name }}!", "{{ count }} items"),
            reportedIn("<p>\n  Hello {{ user.name }}!\n</p><p>{{ count }} items</p>")
        )
    }

    @Test
    fun aTextInsideABlockIsReported() {
        Assertions.assertEquals(listOf("Inside"), reportedIn("@if (ready) { Inside }"))
    }

    @Test
    fun translatedTextsKeysAndCodeAreIgnored() {
        val reported = reportedIn(
            """
            <p>{{ 'save' | translate }}</p>
            <p translate>menu.home</p>
            <p transloco="menu.home"></p>
            <p i18n>Hello</p>
            <input [title]="caption" title2="x">
            <a i18n-title title="Home"></a>
            <a title="{{ caption }}"></a>
            <code>npm install</code>
            <p>— 42</p>
            """.trimIndent()
        )
        Assertions.assertEquals(emptyList<String>(), reported)
    }

    @Test
    fun aTextBesideAnExpressionThatIsNoVariableIsIgnored() {
        Assertions.assertEquals(emptyList<String>(), reportedIn("<p>Updated {{ date | date }}</p><p>Total {{ count() }}</p>"))
    }

    @Test
    fun onlyTheTextOfATagWithoutChildTagIsReported() {
        Assertions.assertEquals(listOf("you"), reportedIn("<p>Hi <b>you</b></p>"))
    }

    @Test
    fun aPlainHtmlFileIsIgnored() {
        myFixture.runWithConfig(config("json")) {
            myFixture.enableInspections(HardcodedAngularTextInspection::class.java)
            myFixture.configureByText("page.html", "<button>Save</button>")
            Assertions.assertTrue(myFixture.doHighlighting().none { it.description == message })
        }
    }

    private fun extract(template: String, key: String, library: String = "\"@ngx-translate/core\": \"15.0.0\""): String {
        var written = ""
        myFixture.runWithConfig(config("json")) {
            openTemplate(template, library)
            myFixture.addFileToProject("assets/test.json", """{"ref": {}}""")
            val fix = myFixture.getAllQuickFixes().single { it.text == hint }
            setTestInputDialog(predefinedTextInputDialog(key))
            myFixture.launchActionAndWait(fix)
            written = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("assets/test.json"))!!.text
        }
        return written
    }

    @Test
    fun theQuickFixWritesNgxTranslatesPipe() {
        val written = extract("<button>Sa<caret>ve changes</button>", "test:ref.save")
        myFixture.checkResult("<button>{{ 'test:ref.save' | translate }}</button>")
        Assertions.assertTrue(written.contains("\"save\"") && written.contains("Save changes"), written)
    }

    @Test
    fun theQuickFixPassesTheInterpolationsAsParameters() {
        val written = extract("<p>Hel<caret>lo {{ user.name }}!</p>", "test:ref.greeting")
        myFixture.checkResult("<p>{{ 'test:ref.greeting' | translate: { name: user.name } }}</p>")
        Assertions.assertTrue(written.contains("\"Hello {{name}}!\""), written)
    }

    @Test
    fun theQuickFixBindsAnAttribute() {
        extract("""<input placeholder="Your na<caret>me">""", "test:ref.name")
        myFixture.checkResult("""<input [placeholder]="'test:ref.name' | translate">""")
    }

    @Test
    fun theQuickFixWritesTranslocosPipeInATranslocoProject() {
        extract("<button>Sa<caret>ve</button>", "test:ref.save", "\"@jsverse/transloco\": \"7.0.0\"")
        myFixture.checkResult("<button>{{ 'test:ref.save' | transloco }}</button>")
    }

    /** A component whose inline template, a fragment of Angular's dialect injected into the literal, is [literal]. */
    private fun openInlineTemplate(literal: String) {
        angularProject()
        myFixture.enableInspections(HardcodedAngularTextInspection::class.java)
        myFixture.configureByText(
            "inline.component.ts",
            "import { Component } from '@angular/core';\n" +
                "@Component({ selector: 'app-inline', template: $literal })\nexport class InlineComponent {}"
        )
    }

    @Test
    fun anInlineTemplateIsReadLikeATemplateFile() {
        var reported = emptyList<String>()
        myFixture.runWithConfig(config("json")) {
            openInlineTemplate("`<button>Save</button><p translate>menu.home</p><input placeholder=\"Name\">`")
            reported = myFixture.doHighlighting()
                .filter { it.severity == HighlightSeverity.WEAK_WARNING && it.description == message }
                .map { it.text }
        }
        Assertions.assertEquals(listOf("Save", "Name"), reported)
    }

    private fun extractInline(literal: String, key: String) = myFixture.runWithConfig(config("json")) {
        openInlineTemplate(literal)
        myFixture.addFileToProject("assets/test.json", """{"ref": {}}""")
        val fix = myFixture.getAllQuickFixes().single { it.text == hint }
        setTestInputDialog(predefinedTextInputDialog(key))
        myFixture.launchActionAndWait(fix)
    }

    @Test
    fun theQuickFixWritesThePipeIntoAnInlineTemplate() {
        extractInline("`<button>Sa<caret>ve</button>`", "test:ref.save")
        myFixture.checkResult(
            "import { Component } from '@angular/core';\n" +
                "@Component({ selector: 'app-inline', template: `<button>{{ 'test:ref.save' | translate }}</button>` })\n" +
                "export class InlineComponent {}"
        )
    }

    @Test
    fun theQuickFixEscapesTheQuotesOfAnInlineTemplatesLiteral() {
        extractInline("'<input placeholder=\"Your na<caret>me\">'", "test:ref.name")
        myFixture.checkResult(
            "import { Component } from '@angular/core';\n" +
                "@Component({ selector: 'app-inline', template: '<input [placeholder]=\"\\'test:ref.name\\' | translate\">' })\n" +
                "export class InlineComponent {}"
        )
    }
}
