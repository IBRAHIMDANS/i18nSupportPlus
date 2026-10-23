package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Workspace
import com.ibrahimdans.i18n.WorkspaceFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/** [project] as a [Workspace]: what the plugin hands the technologies. */
class IdeWorkspace(val project: Project) : Workspace {
    override val basePath: String get() = project.basePath.orEmpty()
}

/** [virtualFile] as a [WorkspaceFile]. */
class IdeWorkspaceFile(val virtualFile: VirtualFile) : WorkspaceFile {
    override val path: String get() = virtualFile.path
}

/** The project behind [this]: in the plugin, every workspace is an [IdeWorkspace]. */
val Workspace.project: Project get() = (this as IdeWorkspace).project

/** The file behind [this]: in the plugin, every workspace file is an [IdeWorkspaceFile]. */
val WorkspaceFile.virtualFile: VirtualFile get() = (this as IdeWorkspaceFile).virtualFile

/** The scopes the technologies declare in [project] ([com.ibrahimdans.i18n.TranslationTechnology.keyScopes]). */
fun keyScopesOf(project: Project): Set<String> {
    val workspace = IdeWorkspace(project)
    return Extensions.TECHNOLOGY.extensionList.flatMapTo(mutableSetOf()) { it.keyScopes(workspace) }
}

/** The scope a technology says [file] holds — a Transloco scope file — or null. */
fun keyScopeOf(file: PsiFile): String? {
    val virtualFile = file.originalFile.virtualFile ?: return null
    val workspace = IdeWorkspace(file.project)
    return Extensions.TECHNOLOGY.extensionList.firstNotNullOfOrNull { it.keyScopeOf(workspace, IdeWorkspaceFile(virtualFile)) }
}
