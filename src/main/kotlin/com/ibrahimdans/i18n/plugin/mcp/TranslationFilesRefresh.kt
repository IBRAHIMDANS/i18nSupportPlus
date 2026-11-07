package com.ibrahimdans.i18n.plugin.mcp

import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

/**
 * Brings the IDE's view of the translation files up to date with the disk before an MCP tool reads them.
 *
 * An agent often writes the files itself (an edit, a script, a `git checkout`) and then asks a tool. The IDE
 * only sees such a change once its file watcher or a refresh catches it, so the tool answered from the old
 * content: 92 keys still missing right after the agent wrote them all, or none missing after a reset.
 */
internal object TranslationFilesRefresh {

    /** Refreshes, synchronously, the folders holding [project]'s translation files; call it outside a read action. */
    suspend fun refresh(project: Project) {
        val files = readAction {
            TranslationDataLoader.findSources(project).mapNotNull { it.tree?.value()?.containingFile?.virtualFile }
        }
        val (folders, parents) = foldersOf(files)
        if (folders.isNotEmpty()) VfsUtil.markDirtyAndRefresh(false, true, true, *folders.toTypedArray())
        if (parents.isNotEmpty()) VfsUtil.markDirtyAndRefresh(false, false, true, *parents.toTypedArray())
    }

    /**
     * The folders of [files], refreshed with their content, and the folders above them, refreshed alone.
     *
     * The folder above catches a locale the agent added (`it/` next to `en/`) without walking the whole
     * project when the files sit at its root, as `locales/en.json` next to `node_modules` would.
     */
    internal fun foldersOf(files: Collection<VirtualFile>): Pair<List<VirtualFile>, List<VirtualFile>> {
        val folders = files.mapNotNull { it.parent }.distinct()
        val parents = folders.mapNotNull { it.parent }.distinct() - folders.toSet()
        return folders to parents
    }
}
