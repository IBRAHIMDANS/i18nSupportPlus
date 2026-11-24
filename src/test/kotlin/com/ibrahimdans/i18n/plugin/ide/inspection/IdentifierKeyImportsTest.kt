package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.psi.PsiBinaryFile
import com.intellij.psi.PsiDocumentManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdentifierKeyImportsTest : PlatformBaseTest() {

    /** A font replaced on disk has no text: reading it threw "Attempt to load text for binary file". */
    @Test
    fun testABinaryFileChangedOnDiskIsNotRead() {
        val imports = IdentifierKeyImports.getInstance(myFixture.project)
        val file = myFixture.tempDirFixture.createFile("fonts/Walkway_Black.unknownfont")
        runWriteAction { file.setBinaryContent(byteArrayOf(0, 1, 0, 0, 0, 19)) }
        // Held until the end: only a file whose PSI is loaded is reloaded.
        val psi = myFixture.psiManager.findFile(file)
        assertInstanceOf(PsiBinaryFile::class.java, psi)
        val before = imports.modificationCount

        runWriteAction { file.setBinaryContent(byteArrayOf(0, 1, 0, 0, 0, 20, 7)) }

        assertEquals(before, imports.modificationCount)
        assertTrue(psi!!.isValid)
    }

    @Test
    fun testAnImportAddedIsCounted() {
        val imports = IdentifierKeyImports.getInstance(myFixture.project)
        myFixture.configureByText("App.ts", "const a = 1;\n")
        val before = imports.modificationCount

        WriteCommandAction.runWriteCommandAction(myFixture.project) {
            myFixture.editor.document.insertString(0, "import * as m from './paraglide/messages.js';\n")
            PsiDocumentManager.getInstance(myFixture.project).commitAllDocuments()
        }

        assertTrue(imports.modificationCount > before)
    }
}
