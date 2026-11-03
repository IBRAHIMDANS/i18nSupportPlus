package com.ibrahimdans.i18n.extensions.technology.angulari18n

import com.ibrahimdans.i18n.extensions.lang.js.HardcodedAngularTextInspection
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractionTestBase
import com.ibrahimdans.i18n.plugin.ide.actions.RenameI18nKeyHandler
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.FrameworkDetector
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiPolyVariantReference
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Angular's own i18n, written by the plugin: a template's text extracted into an `i18n="@@id"` marker and a
 * new XLIFF unit, an id renamed in the markers and every XLIFF file, and the module preset the wizard proposes.
 */
class AngularNativeI18nActionsTest : ExtractionTestBase() {

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
      <trans-unit id="cart.terms" datatype="html">
        <source>Terms</source>
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
      <trans-unit id="cart.terms" datatype="html">
        <source>Terms</source>
        <target>Conditions</target>
      </trans-unit>
    </body>
  </file>
</xliff>
"""
    }

    @AfterEach
    fun restoreInputDialog() {
        setTestInputDialog(TestInputDialog.DEFAULT)
    }

    private fun addWorkspace() {
        addFileToProject("angular.json", ANGULAR_JSON)
        addFileToProject("src/locale/messages.xlf", SOURCE)
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

    // ── Extraction ────────────────────────────────────────────────────────────

    private fun extract(template: String, id: String, config: Config = Config()) = myFixture.runWithConfig(config) {
        addWorkspace()
        myFixture.enableInspections(HardcodedAngularTextInspection::class.java)
        openTemplate(template)
        val fix = myFixture.getAllQuickFixes().single { it.text == hint }
        setTestInputDialog(predefinedTextInputDialog(id))
        myFixture.launchActionAndWait(fix)
    }

    @Test
    fun aTextIsMarkedWithItsIdAndBecomesAUnitOfTheXliffFiles() {
        extract("<button class=\"primary\">Sa<caret>ve changes</button>", "cart.save")

        myFixture.checkResult("<button class=\"primary\" i18n=\"@@cart.save\">Save changes</button>")
        val source = textOf("src/locale/messages.xlf")
        Assertions.assertTrue(source.contains("<trans-unit id=\"cart.save\" datatype=\"html\">"), source)
        Assertions.assertTrue(source.contains("<source>Save changes</source>"), source)
        Assertions.assertTrue(textOf("src/locale/messages.fr.xlf").contains("<trans-unit id=\"cart.save\""), textOf("src/locale/messages.fr.xlf"))
    }

    @Test
    fun anAttributeIsMarkedBesideIt() {
        extract("""<input placeholder="Your na<caret>me">""", "form.name")

        myFixture.checkResult("""<input placeholder="Your name" i18n-placeholder="@@form.name">""")
        Assertions.assertTrue(textOf("src/locale/messages.xlf").contains("<source>Your name</source>"))
    }

    @Test
    fun aTextOutsideAnyTagIsWrappedInAnNgContainer() {
        extract("@if (ready) { Ins<caret>ide }", "home.inside")

        myFixture.checkResult("@if (ready) { <ng-container i18n=\"@@home.inside\">Inside</ng-container> }")
    }

    /** The module preset names Angular's own i18n: its markers stay read, `t()` calls of other libraries are not. */
    @Test
    fun underTheAngularI18nPresetAMarkerStillResolves() = myFixture.runWithConfig(
        Config(modules = listOf(ModuleConfig(name = "shop", rootDirectory = "src", preset = FrameworkDetector.ANGULAR_I18N)))
    ) {
        addWorkspace()
        openTemplate("""<h1 i18n="@@home.ti<caret>tle">Welcome</h1>""")

        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference

        Assertions.assertEquals(2, reference.multiResolve(false).size)
    }

    // ── Rename ────────────────────────────────────────────────────────────────

    private fun renameAtCaret(newId: String) {
        setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = newId
            override fun show(message: String, validator: InputValidator?): String = newId
        })
        RenameI18nKeyHandler().invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)
    }

    @Test
    fun renamingAnIdRenamesEveryMarkerAndItsUnitInEachFile() = myFixture.runWithConfig(Config()) {
        addWorkspace()
        addFileToProject("src/app/home.ts", "const title = \$localize`:@@home.title:Welcome`;\nconst other = \$localize`:@@home.titles:Other`;\n")
        openTemplate("""<h1 i18n="Page title@@home.ti<caret>tle">Welcome</h1><p i18n="@@cart.terms">Terms</p>""")

        renameAtCaret("home.heading")

        myFixture.checkResult("""<h1 i18n="Page title@@home.heading">Welcome</h1><p i18n="@@cart.terms">Terms</p>""")
        Assertions.assertEquals(
            "const title = \$localize`:@@home.heading:Welcome`;\nconst other = \$localize`:@@home.titles:Other`;\n",
            textOf("src/app/home.ts"),
        )
        for (file in listOf("src/locale/messages.xlf", "src/locale/messages.fr.xlf")) {
            val text = textOf(file)
            Assertions.assertTrue(text.contains("<trans-unit id=\"home.heading\"") && !text.contains("\"home.title\""), text)
        }
    }

    /** Reviews on for the project, [id] approved in French with its translation. */
    private fun approveInFrench(id: String, value: String): ReviewStore {
        val store = ReviewStore.getInstance(project).apply { reviewer = { "Alice <alice@example.com>" } }
        val root = ReviewLocation.rootOf(project, null)!!
        WriteCommandAction.runWriteCommandAction(project) {
            store.enable(root)
            store.decide(root, "fr", ReviewKey("translation", id), ReviewState.APPROVED, value)
        }
        return store
    }

    @Test
    fun renamingAnIdMovesItsReviewAndOneUndoBringsItBack() = myFixture.runWithConfig(Config()) {
        addWorkspace()
        openTemplate("""<h1 i18n="@@home.ti<caret>tle">Welcome</h1>""")
        val store = approveInFrench("home.title", "Bienvenue")
        val root = ReviewLocation.rootOf(project, null)!!

        renameAtCaret("home.heading")

        Assertions.assertEquals(setOf(ReviewKey("translation", "home.heading")), store.reviews(root, "fr").keys)
        Assertions.assertEquals(ReviewState.APPROVED, store.reviews(root, "fr").getValue(ReviewKey("translation", "home.heading")).state)

        UndoManager.getInstance(project).undo(null)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        Assertions.assertEquals(setOf(ReviewKey("translation", "home.title")), store.reviews(root, "fr").keys)
    }

    @Test
    fun renamingAnIdLeavesTheReviewsOfTheIdsThatExtendItAfterADot() = myFixture.runWithConfig(Config()) {
        addWorkspace()
        openTemplate("""<h1 i18n="@@home.ti<caret>tle">Welcome</h1>""")
        val store = approveInFrench("home.title", "Bienvenue")
        val root = ReviewLocation.rootOf(project, null)!!
        WriteCommandAction.runWriteCommandAction(project) {
            store.decide(root, "fr", ReviewKey("translation", "home.title.short"), ReviewState.APPROVED, "Salut")
        }

        renameAtCaret("home.heading")

        // An id is flat: `home.title.short` is another unit, not a child of `home.title`.
        Assertions.assertEquals(
            setOf(ReviewKey("translation", "home.heading"), ReviewKey("translation", "home.title.short")),
            store.reviews(root, "fr").keys,
        )

        UndoManager.getInstance(project).undo(null)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        Assertions.assertEquals(
            setOf(ReviewKey("translation", "home.title"), ReviewKey("translation", "home.title.short")),
            store.reviews(root, "fr").keys,
        )
    }

    @Test
    fun anIdAnotherUnitHasIsRefused() = myFixture.runWithConfig(Config()) {
        addWorkspace()
        val edits = ReadAction.compute<List<RenameI18nKeyHandler.Edit>?, RuntimeException> {
            AngularI18nIdRename.editsOf(project, "home.title", "cart.terms")
        }
        Assertions.assertNull(edits)
    }
}
