package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SortAllTranslationFilesActionTest : PlatformBaseTest() {

    private fun unsorted(): List<VirtualFile> = ReadAction.compute<List<VirtualFile>, RuntimeException> {
        SortAllTranslationFilesAction.unsortedFiles(project)
    }

    private fun text(path: String): String = ReadAction.compute<String, RuntimeException> {
        String(myFixture.findFileInTempDir(path)!!.contentsToByteArray(), Charsets.UTF_8)
    }

    private fun documentText(path: String): String = ReadAction.compute<String, RuntimeException> {
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(path)!!)!!.text
    }

    @Test
    fun everyLocaleIsSortedInOneUndoableCommand() = myFixture.runWithConfig(Config()) {
        myFixture.addFileToProject("locales/en/common.json", """{"b": "B", "a": "A"}""")
        myFixture.addFileToProject("locales/fr/common.json", """{"b": "Bé", "a": "À"}""")

        val sorted = SortAllTranslationFilesAction.sortAll(project, unsorted())

        assertEquals(2, sorted.size, "$sorted")
        assertEquals("""{"a": "A", "b": "B"}""", documentText("locales/en/common.json"))
        assertEquals("""{"a": "À", "b": "Bé"}""", documentText("locales/fr/common.json"))

        // One undo restores both files.
        val en = myFixture.findFileInTempDir("locales/en/common.json")!!
        val editor = FileEditorManager.getInstance(project).openFile(en, true).first() as TextEditor
        UndoManager.getInstance(project).undo(editor)
        assertEquals("""{"b": "B", "a": "A"}""", documentText("locales/en/common.json"))
        assertEquals("""{"b": "Bé", "a": "À"}""", documentText("locales/fr/common.json"))
    }

    @Test
    fun aFileAlreadyInOrderIsLeftUntouched() = myFixture.runWithConfig(Config()) {
        myFixture.addFileToProject("locales/en/common.json", """{"a": {"x": "1", "y": "2"}, "b": "B"}""")
        myFixture.addFileToProject("locales/fr/common.json", """{"a": {"y": "2", "x": "1"}, "b": "B"}""")

        val unsorted = unsorted()

        assertEquals(listOf("locales/fr/common.json"), unsorted.map { it.path.substringAfter("/src/") })
        SortAllTranslationFilesAction.sortAll(project, unsorted)
        assertEquals("""{"a": {"x": "1", "y": "2"}, "b": "B"}""", text("locales/en/common.json"))
    }

    @Test
    fun aFileThatIsNotJsonIsIgnored() = myFixture.runWithConfig(Config()) {
        myFixture.addFileToProject("locales/en/common.yaml", "b: B\na: A\n")
        myFixture.addFileToProject("locales/fr/common.json", """{"b": "B", "a": "A"}""")

        val unsorted = unsorted()

        assertEquals(listOf("locales/fr/common.json"), unsorted.map { it.path.substringAfter("/src/") })
        SortAllTranslationFilesAction.sortAll(project, unsorted)
        assertEquals("b: B\na: A\n", text("locales/en/common.yaml"))
    }

    @Test
    fun anArbCatalogueIsSortedWithItsMetadataNextToEachKey() = myFixture.runWithConfig(Config()) {
        myFixture.addFileToProject(
            "lib/l10n/app_en.arb",
            """{"zebra": "Zebra", "@zebra": {"description": "An animal"}, "@@locale": "en", "apple": "Apple"}"""
        )
        myFixture.addFileToProject("lib/l10n/app_fr.arb", """{"@@locale": "fr", "apple": "Pomme", "zebra": "Zèbre"}""")

        val unsorted = unsorted()

        assertEquals(listOf("lib/l10n/app_en.arb"), unsorted.map { it.path.substringAfter("/src/") })
        SortAllTranslationFilesAction.sortAll(project, unsorted)
        assertEquals(
            """{"@@locale": "en", "apple": "Apple", "zebra": "Zebra", "@zebra": {"description": "An animal"}}""",
            documentText("lib/l10n/app_en.arb")
        )
        assertEquals("""{"@@locale": "fr", "apple": "Pomme", "zebra": "Zèbre"}""", text("lib/l10n/app_fr.arb"))
    }

    @Test
    fun theActionShowsOutsideATranslationFile()= myFixture.runWithConfig(Config()) {
        myFixture.addFileToProject("locales/en/common.json", """{"a": "A"}""")
        val code = myFixture.configureByText("App.tsx", "export const App = () => null;")
        val action = ActionManager.getInstance().getAction("com.ibrahimdans.i18n.SortAllTranslationFiles")
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.PSI_FILE, code)
            .build()

        for (place in listOf(ActionPlaces.MAIN_MENU, ActionPlaces.EDITOR_POPUP)) {
            val event = AnActionEvent.createEvent(context, null, place, com.intellij.openapi.actionSystem.ActionUiKind.NONE, null)
            action.update(event)
            assertTrue(event.presentation.isEnabledAndVisible, place)
        }
    }
}
