package com.ibrahimdans.i18n.plugin.mcp

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Writes into the project's instruction file for AI agents the line that makes them use the
 * plugin's `i18n_*` MCP tools rather than edit the translation files themselves.
 *
 * A client such as Claude Code loads a tool's definition only once the agent looks for it: asked
 * to translate without a word about the tools, an agent often edits the files by hand, skipping
 * the placeholder and plural checks the tools apply. One line in `AGENTS.md`, `CLAUDE.md` or
 * `.github/copilot-instructions.md` settles it.
 *
 * The line goes between two HTML comments, invisible once rendered, so that running the action
 * again — after adding a module, say — replaces it instead of adding a second one. The rest of
 * the file is never touched. One command: a single undo removes it, or the file it created.
 *
 * Declared in `mcpConfig.xml`: without the IDE's MCP server there are no tools to point to.
 */
class WriteAgentInstructionsAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.guessProjectDir() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val base = project.guessProjectDir() ?: return
        val block = AgentInstructions.block(AgentInstructions.translationFolders(Settings.getInstance(project).config()))
        val targets = targetsOf(base)
        val dialog = Dialog(project, targets, block)
        if (!dialog.showAndGet()) return
        val file = write(project, base, dialog.selected().path, block)
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /** An instruction file the block can go to, at [path] relative to the project directory. */
    internal data class Target(val path: String, val exists: Boolean, val hasBlock: Boolean)

    private class Dialog(project: Project, private val targets: List<Target>, block: String) : DialogWrapper(project) {

        private val combo = ComboBox(targets.toTypedArray()).apply {
            renderer = SimpleListCellRenderer.create("") { target ->
                when {
                    !target.exists -> PluginBundle.message("agent.instructions.new", target.path)
                    target.hasBlock -> PluginBundle.message("agent.instructions.replace", target.path)
                    else -> target.path
                }
            }
            selectedItem = defaultTarget(targets)
        }

        private val preview = JBTextArea(block.trimEnd()).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            rows = 5
            columns = 70
        }

        init {
            title = PluginBundle.message("agent.instructions.title")
            setOKButtonText(PluginBundle.message("agent.instructions.ok"))
            init()
        }

        fun selected(): Target = combo.selectedItem as? Target ?: defaultTarget(targets)

        override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                add(JBLabel(PluginBundle.message("agent.instructions.file")), BorderLayout.WEST)
                add(combo, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(JBLabel(PluginBundle.message("agent.instructions.preview")), BorderLayout.NORTH)
                add(JBScrollPane(preview), BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }
    }

    internal companion object {

        /** The instruction files agents read, the cross-agent one first. */
        val CANDIDATES = listOf("AGENTS.md", "CLAUDE.md", ".github/copilot-instructions.md")

        /**
         * The files offered: those of [CANDIDATES] that exist under [base], and `AGENTS.md` even
         * when it does not, to be created. A missing `CLAUDE.md` is not offered: a project with
         * neither file gets the one every agent reads.
         */
        fun targetsOf(base: VirtualFile): List<Target> = CANDIDATES.mapNotNull { path ->
            val file = base.findFileByRelativePath(path)
            if (file == null || file.isDirectory) {
                if (path == CANDIDATES.first()) Target(path, exists = false, hasBlock = false) else null
            } else {
                Target(path, exists = true, hasBlock = AgentInstructions.BEGIN in textOf(file))
            }
        }

        /** The file holding the block already, else the first existing one, else `AGENTS.md` to create. */
        fun defaultTarget(targets: List<Target>): Target =
            targets.firstOrNull { it.hasBlock } ?: targets.firstOrNull { it.exists } ?: targets.first()

        /**
         * Writes [block] into the file at [path] under [base] — created, with its folders, when
         * missing — in one undoable command, and returns the file.
         */
        fun write(project: Project, base: VirtualFile, path: String, block: String): VirtualFile {
            return WriteCommandAction.writeCommandAction(project)
                .withName(PluginBundle.message("agent.instructions.command"))
                .compute<VirtualFile, RuntimeException> {
                    val file = base.findFileByRelativePath(path) ?: run {
                        val folder = path.substringBeforeLast('/', "")
                        val parent = if (folder.isEmpty()) base else VfsUtil.createDirectoryIfMissing(base, folder)!!
                        parent.createChildData(this, path.substringAfterLast('/'))
                    }
                    val document = FileDocumentManager.getInstance().getDocument(file)
                        ?: error("no document for $path")
                    document.setText(AgentInstructions.merge(document.text, block))
                    FileDocumentManager.getInstance().saveDocument(document)
                    file
                }
        }

        private fun textOf(file: VirtualFile): String =
            FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: VfsUtil.loadText(file)
    }
}

/** The block [WriteAgentInstructionsAction] writes, and how it goes into a file. Platform-free. */
internal object AgentInstructions {

    const val BEGIN = "<!-- i18n-support-plus:agent-instructions -->"
    const val END = "<!-- /i18n-support-plus:agent-instructions -->"

    /** The block, naming the translation [folders] when there are any. */
    fun block(folders: List<String>): String {
        val where = if (folders.isEmpty()) "" else " (${folders.joinToString(", ")})"
        return "$BEGIN\nFor translation files$where, use the i18n_* MCP tools instead of editing the files.\n$END\n"
    }

    /**
     * [existing] with [block] in it: in place of the block written before, or after the text,
     * separated by a blank line. An empty file becomes the block alone.
     */
    fun merge(existing: String, block: String): String {
        if (existing.isBlank()) return block
        val start = existing.indexOf(BEGIN)
        val end = if (start < 0) -1 else existing.indexOf(END, start)
        if (end >= 0) return existing.substring(0, start) + block.removeSuffix("\n") + existing.substring(end + END.length)
        val separator = when {
            existing.endsWith("\n\n") -> ""
            existing.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        return existing + separator + block
    }

    /**
     * The folders holding the translation files, relative to the project: each active module's root
     * directory followed by the fixed start of its path template (`apps/web` + `locales/{lang}/{ns}.json`
     * gives `apps/web/locales`), or the translations root without modules.
     */
    fun translationFolders(config: Config): List<String> {
        val folders = if (config.modules.isEmpty()) {
            listOf(config.normalizedTranslationsRoot())
        } else {
            config.modules.filter { it.enabled }.map { module ->
                val fixed = module.pathTemplate.substringBefore('{').substringBeforeLast('/', "")
                listOf(module.rootDirectory, fixed)
                    .map { it.replace('\\', '/').trim().trim('/').removePrefix("./") }
                    .filter { it.isNotEmpty() && it != "." }
                    .joinToString("/")
            }
        }
        return folders.map { it.trim('/') }.filter { it.isNotEmpty() }.distinct()
    }
}
