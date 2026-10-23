package com.ibrahimdans.i18n.extensions.technology.transloco

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.Technology
import com.ibrahimdans.i18n.Workspace
import com.ibrahimdans.i18n.WorkspaceFile
import com.ibrahimdans.i18n.plugin.utils.pathToRoot
import com.ibrahimdans.i18n.plugin.utils.project
import com.ibrahimdans.i18n.plugin.utils.virtualFile
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager

/**
 * Angular Transloco (`@jsverse/transloco`, formerly `@ngneat/transloco`).
 *
 * Publishes `t`, the function the structural directive hands its template
 * (`*transloco="let t"`, then `t('key')`), so a module preset set to Transloco keeps it. The service
 * calls (`translocoService.translate('key')`) and the `| transloco` pipe are recognised from their
 * own syntax by `TranslocoExtractor` and `TranslocoPipeExtractor`.
 *
 * Declares each scope file ([TranslocoScopes]) as a source of the namespace named after its scope,
 * and the scopes as [keyScopes], so `admin.title` resolves in `i18n/admin/en.json`.
 */
class TranslocoTechnology : Technology {
    override fun frameworkId(): String = "transloco"

    override fun translationFunctionNames(): List<String> = listOf("t")

    override fun initialize(workspace: Workspace) {}

    override fun cfgNamespaces(): List<String> = emptyList()

    override fun findSourcesByConfiguration(workspace: Workspace): List<LocalizationSource> {
        val project = workspace.project
        val scopeFiles = TranslocoScopes.scopeFiles(project)
        if (scopeFiles.isEmpty()) return emptyList()
        val basePath = project.basePath.orEmpty()
        return ReadAction.compute<List<LocalizationSource>, RuntimeException> {
            scopeFiles.mapNotNull { (scope, locale, virtualFile) ->
                val file = PsiManager.getInstance(project).findFile(virtualFile) ?: return@mapNotNull null
                val localization = Extensions.LOCALIZATION.extensionList
                    .firstOrNull { localization -> localization.types().any { it.languageFileType == file.fileType } } ?: return@mapNotNull null
                LocalizationSource(
                    localization.elementsTree(file),
                    file.name,
                    virtualFile.parent.name,
                    pathToRoot(basePath, virtualFile.parent.path).trim('/') + '/' + file.name,
                    localization,
                    locale = locale,
                    namespace = scope,
                )
            }
        }
    }

    override fun keyScopes(workspace: Workspace): Set<String> = TranslocoScopes.scopes(workspace.project)

    override fun keyScopeOf(workspace: Workspace, file: WorkspaceFile): String? =
        TranslocoScopes.scopeOf(workspace.project, file.virtualFile)
}
