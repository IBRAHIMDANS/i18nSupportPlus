package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The Table View edited as a spreadsheet: where Tab, Shift+Tab and Enter lead ([nextEditableCell]),
 * and one undo per edited cell.
 */
class TableViewPanelKeyboardTest : PlatformBaseTest() {

    // Key | en | fr | de | Usage — the key and Usage columns are not editable.
    private val columnCount = 5
    private val editable: (Int) -> Boolean = { it in 1..3 }

    private fun next(row: Int, column: Int, rowStep: Int = 0, columnStep: Int = 0, rowCount: Int = 3) =
        nextEditableCell(row, column, rowStep, columnStep, rowCount, columnCount, editable)

    @Test
    fun tabMovesToTheNextLocale() {
        Assertions.assertEquals(0 to 2, next(0, 1, columnStep = 1))
    }

    @Test
    fun tabSkipsUsageAndKeyToTheFirstLocaleOfTheNextRow() {
        Assertions.assertEquals(1 to 1, next(0, 3, columnStep = 1))
    }

    @Test
    fun tabFromTheKeyColumnGoesToTheFirstLocale() {
        Assertions.assertEquals(0 to 1, next(0, 0, columnStep = 1))
    }

    @Test
    fun shiftTabGoesBackToTheLastLocaleOfThePreviousRow() {
        Assertions.assertEquals(0 to 3, next(1, 1, columnStep = -1))
    }

    @Test
    fun theEdgesOfTheTableStopTheCaret() {
        Assertions.assertNull(next(2, 3, columnStep = 1), "Tab on the last locale of the last row")
        Assertions.assertNull(next(0, 1, columnStep = -1), "Shift+Tab on the first locale of the first row")
        Assertions.assertNull(next(2, 2, rowStep = 1), "Enter on the last row")
    }

    @Test
    fun enterMovesDownInTheSameColumn() {
        Assertions.assertEquals(1 to 2, next(0, 2, rowStep = 1))
    }

    private fun valueAt(path: String, vararg key: String): String? =
        ReadAction.compute<String?, RuntimeException> {
            val vf = myFixture.findFileInTempDir(path) ?: return@compute null
            val file = PsiManager.getInstance(project).findFile(vf) as? JsonFile ?: return@compute null
            var node = file.topLevelValue as? JsonObject ?: return@compute null
            for (i in 0 until key.size - 1) node = node.findProperty(key[i])?.value as? JsonObject ?: return@compute null
            (node.findProperty(key.last())?.value as? JsonStringLiteral)?.value
        }

    /** Moving on with Tab commits each cell: each one must be its own undo step. */
    @Test
    fun oneUndoRestoresTheEditedCell() {
        addFileToProject("locales/fr/common.json", """{"menu":{"home":"Accueil","about":"À propos"}}""")
        myFixture.openFileInEditor(myFixture.findFileInTempDir("locales/fr/common.json"))
        val viewModel = TableViewModel()

        Assertions.assertTrue(viewModel.saveValue(project, "common:menu.home", "fr", "Maison"))
        Assertions.assertTrue(viewModel.saveValue(project, "common:menu.about", "fr", "À propos de nous"))

        val editor = FileEditorManagerEx.getInstanceEx(project).selectedEditor
        Assertions.assertTrue(UndoManager.getInstance(project).isUndoAvailable(editor), "an in-place edit must be undoable")
        UndoManager.getInstance(project).undo(editor)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        Assertions.assertEquals("À propos", valueAt("locales/fr/common.json", "menu", "about"), "one undo restores the last cell")
        Assertions.assertEquals("Maison", valueAt("locales/fr/common.json", "menu", "home"), "and only that cell")
    }
}
