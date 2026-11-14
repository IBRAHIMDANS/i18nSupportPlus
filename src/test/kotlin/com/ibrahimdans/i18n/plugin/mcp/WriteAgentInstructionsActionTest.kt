package com.ibrahimdans.i18n.plugin.mcp

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class WriteAgentInstructionsActionTest : PlatformBaseTest() {

    private val block = AgentInstructions.block(listOf("public/locales"))

    private fun base(): VirtualFile = myFixture.tempDirFixture.findOrCreateDir("root")

    private fun textOf(file: VirtualFile) = FileDocumentManager.getInstance().getDocument(file)!!.text

    @Test
    fun `a project without instruction file is offered AGENTS md, to create`() {
        val targets = WriteAgentInstructionsAction.targetsOf(base())

        Assertions.assertEquals(listOf(WriteAgentInstructionsAction.Target("AGENTS.md", exists = false, hasBlock = false)), targets)
    }

    @Test
    fun `existing files are offered, the one holding the block chosen first`() {
        addFileToProject("root/AGENTS.md", "# Agents\n")
        addFileToProject("root/CLAUDE.md", "# Claude\n\n$block")

        val targets = WriteAgentInstructionsAction.targetsOf(base())

        Assertions.assertEquals(listOf("AGENTS.md", "CLAUDE.md"), targets.map { it.path })
        Assertions.assertEquals("CLAUDE.md", WriteAgentInstructionsAction.defaultTarget(targets).path)
    }

    @Test
    fun `a missing file is created with the block`() {
        val file = WriteAgentInstructionsAction.write(project, base(), "AGENTS.md", block)

        Assertions.assertEquals("AGENTS.md", file.name)
        Assertions.assertEquals(block, textOf(file))
    }

    @Test
    fun `an existing file gets the block at its end, the rest untouched`() {
        addFileToProject("root/CLAUDE.md", "# Claude\n\nRun the tests.\n")

        val file = WriteAgentInstructionsAction.write(project, base(), "CLAUDE.md", block)

        Assertions.assertEquals("# Claude\n\nRun the tests.\n\n$block", textOf(file))
    }

    @Test
    fun `the block written before is replaced, not duplicated`() {
        addFileToProject("root/AGENTS.md", "# Agents\n\n${AgentInstructions.block(listOf("old"))}")

        val file = WriteAgentInstructionsAction.write(project, base(), "AGENTS.md", block)

        Assertions.assertEquals("# Agents\n\n$block", textOf(file))
    }

    @Test
    fun `one undo takes the block back`() {
        addFileToProject("root/AGENTS.md", "# Agents\n")
        val file = base().findChild("AGENTS.md")!!
        val editor = FileEditorManager.getInstance(project).openFile(file, true).filterIsInstance<TextEditor>().first()

        WriteAgentInstructionsAction.write(project, base(), "AGENTS.md", block)
        UndoManager.getInstance(project).undo(editor)

        Assertions.assertEquals("# Agents\n", textOf(file))
    }
}
