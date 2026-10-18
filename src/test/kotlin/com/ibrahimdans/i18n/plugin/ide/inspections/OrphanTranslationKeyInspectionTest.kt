package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.OrphanTranslationKeyInspection
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrphanTranslationKeyInspectionTest : PlatformBaseTest() {

    /** The orphan-key warnings of the file created at [path], each with the text it underlines. */
    private fun orphanWarnings(path: String, content: String): List<Pair<String, String>> {
        myFixture.enableInspections(OrphanTranslationKeyInspection::class.java)
        val file = myFixture.addFileToProject(path, content)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val text = myFixture.file.text
        return myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.WARNING }
            .filter { it.description?.contains("is not in reference locale") == true }
            .map { it.description!! to text.substring(it.startOffset, it.endOffset) }
    }

    private fun keysOf(warnings: List<Pair<String, String>>) = warnings.map { it.first.substringAfter("'").substringBefore("'") }

    private fun text(path: String): String {
        val virtualFile = myFixture.findFileInTempDir(path)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return PsiManager.getInstance(project).findFile(virtualFile)!!.text.filterNot { it.isWhitespace() }
    }

    private fun undo() {
        UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    // Detection

    @Test
    fun aKeyAbsentFromTheReferenceIsReportedOnItsName() {
        myFixture.addFileToProject("locales/en/common.json", """{"user": {"name": "Name"}}""")
        val warnings = orphanWarnings("locales/fr/common.json", """{"user": {"name": "Nom", "nmae": "Nom"}}""")
        Assertions.assertEquals(1, warnings.size, "$warnings")
        assertTrue(warnings.single().first.contains("'user.nmae'") && warnings.single().first.contains("'en'"), "$warnings")
        Assertions.assertEquals("\"nmae\"", warnings.single().second)
    }

    /** `a` is in the reference, `a.b` is not: the problem sits on `b`, not on `a`. */
    @Test
    fun aNestedOrphanIsReportedOnItsOwnSegment() {
        myFixture.addFileToProject("locales/en/common.json", """{"a": {"c": "C"}}""")
        val warnings = orphanWarnings("locales/fr/common.json", """{"a": {"b": "B", "c": "C"}}""")
        Assertions.assertEquals(listOf("a.b"), keysOf(warnings))
        Assertions.assertEquals("\"b\"", warnings.single().second)
    }

    @Test
    fun theReferenceFileIsNeverReported() {
        myFixture.addFileToProject("locales/fr/common.json", """{"title": "Titre"}""")
        val warnings = orphanWarnings("locales/en/common.json", """{"title": "Title", "onlyInEnglish": "Only"}""")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun aFileMatchingTheReferenceHasNoProblem() {
        myFixture.addFileToProject("locales/en/common.json", """{"title": "Title", "user": {"name": "Name"}, "extra": "x"}""")
        val warnings = orphanWarnings("locales/fr/common.json", """{"title": "Titre", "user": {"name": "Nom"}}""")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    /** Russian spells the plural `en` writes `item_one` / `item_other` with `few` and `many` too. */
    @Test
    fun aLocaleSpecificPluralFormIsNotReported() {
        myFixture.addFileToProject("locales/en/common.json", """{"item_one": "{{count}} item", "item_other": "{{count}} items"}""")
        val warnings = orphanWarnings(
            "locales/ru/common.json",
            """{"item_one": "1", "item_few": "2", "item_many": "5", "item_other": "x"}"""
        )
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun aPluralGroupTheReferenceLacksIsReported() {
        myFixture.addFileToProject("locales/en/common.json", """{"title": "Title"}""")
        val warnings = orphanWarnings("locales/ru/common.json", """{"title": "T", "item_few": "2", "item_other": "x"}""")
        Assertions.assertEquals(setOf("item_few", "item_other"), keysOf(warnings).toSet())
    }

    @Test
    fun flatKeysAreComparedAsWritten() = myFixture.runWithConfig(Config(flatKeys = true)) {
        myFixture.addFileToProject("locales/en/common.json", """{"app.title": "Title"}""")
        val warnings = orphanWarnings("locales/fr/common.json", """{"app.title": "Titre", "app.subtitle": "Sous-titre"}""")
        Assertions.assertEquals(listOf("app.subtitle"), keysOf(warnings))
        Assertions.assertEquals("\"app.subtitle\"", warnings.single().second)
    }

    @Test
    fun anIgnoredLocaleIsSilent() = myFixture.runWithConfig(Config(ignoredLocales = "de")) {
        myFixture.addFileToProject("locales/en/common.json", """{"title": "Title"}""")
        val warnings = orphanWarnings("locales/de/common.json", """{"title": "Titel", "extra": "Extra"}""")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun aYamlOrphanIsReported() {
        myFixture.addFileToProject("locales/en/common.yaml", "user:\n  name: Name\n")
        val warnings = orphanWarnings("locales/fr/common.yaml", "user:\n  name: Nom\n  age: Âge\n")
        Assertions.assertEquals(listOf("user.age"), keysOf(warnings))
        Assertions.assertEquals("age", warnings.single().second)
    }

    // Quick fixes

    @Test
    fun removeDeletesTheKeyFromTheLocaleAndUndoRestoresIt() {
        myFixture.addFileToProject("locales/en/common.json", """{"title": "Title"}""")
        orphanWarnings("locales/fr/common.json", """{"title": "Titre", "extra": "Extra"}""")
        val fix = myFixture.getAllQuickFixes()
            .firstOrNull { it.text == PluginBundle.getMessage("inspection.orphan.key.fix.remove.name", "extra") }
        assertTrue(fix != null, "quick fix should be offered")
        myFixture.launchAction(fix!!)

        Assertions.assertEquals("""{"title":"Titre"}""", myFixture.file.text.filterNot { it.isWhitespace() })
        Assertions.assertEquals("""{"title":"Title"}""", text("locales/en/common.json"), "the reference is left alone")

        undo()
        Assertions.assertEquals("""{"title":"Titre","extra":"Extra"}""", myFixture.file.text.filterNot { it.isWhitespace() })
    }

    @Test
    fun addCreatesTheKeyEmptyInTheReferenceAndUndoTakesItBack() {
        myFixture.addFileToProject("locales/en/common.json", """{"user": {"name": "Name"}}""")
        orphanWarnings("locales/fr/common.json", """{"user": {"name": "Nom", "age": "Âge"}}""")
        val fix = myFixture.getAllQuickFixes()
            .firstOrNull { it.text == PluginBundle.getMessage("inspection.orphan.key.fix.add.name", "user.age", "en") }
        assertTrue(fix != null, "quick fix should be offered")
        myFixture.launchAction(fix!!)
        // The reference is written in a command of its own, run once the platform's ends.
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        Assertions.assertEquals("""{"user":{"name":"Name","age":""}}""", text("locales/en/common.json"))
        val remaining = myFixture.doHighlighting().mapNotNull { it.description }.filter { it.contains("is not in reference locale") }
        assertTrue(remaining.isEmpty(), "the added key must no longer be reported: $remaining")

        undo()
        Assertions.assertEquals("""{"user":{"name":"Name"}}""", text("locales/en/common.json"))
    }

    /** `user` is a string in the reference: adding `user.age` there would overwrite it. */
    @Test
    fun addIsNotOfferedWhenTheReferenceHoldsALeafOnThePath() {
        myFixture.addFileToProject("locales/en/common.json", """{"user": "User"}""")
        val warnings = orphanWarnings("locales/fr/common.json", """{"user": {"age": "Âge"}}""")
        Assertions.assertEquals(listOf("user.age"), keysOf(warnings))
        val fixes = myFixture.getAllQuickFixes().map { it.text }
        assertTrue(fixes.none { it == PluginBundle.getMessage("inspection.orphan.key.fix.add.name", "user.age", "en") }, "$fixes")
        assertTrue(fixes.any { it == PluginBundle.getMessage("inspection.orphan.key.fix.remove.name", "user.age") }, "$fixes")
    }
}
