package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.settings.searchScope
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope

/** [file]'s path relative to [basePath] when it lives under it, absolute otherwise. */
fun ModuleSources.FilePath.Companion.of(file: VirtualFile, basePath: String): ModuleSources.FilePath =
    of(file.path, basePath)

/** The module whose root directory holds [file], or null in a project without modules or for a file in none. */
fun ModuleSources.ownerOf(project: Project, file: VirtualFile?): ModuleConfig? {
    val modules = Settings.getInstance(project).config().modules
    if (file == null || modules.isEmpty()) return null
    return owner(modules, ModuleSources.FilePath.of(file, project.basePath ?: ""))
}

/**
 * Where the code using a key is searched: the project's search scope, narrowed to [module]'s root
 * directory — a key of `apps/web`'s files is used by `apps/web`'s code, not `apps/admin`'s. The whole
 * search scope for no module. Matched on paths as [owner] matches them, so the two never disagree.
 */
fun ModuleSources.searchScope(project: Project, module: ModuleConfig?): GlobalSearchScope {
    val projectScope = Settings.getInstance(project).config().searchScope(project)
    if (module == null || module.rootDirectory.isBlank()) return projectScope
    val basePath = project.basePath ?: ""
    return object : DelegatingGlobalSearchScope(projectScope, module) {
        override fun contains(file: VirtualFile): Boolean =
            super.contains(file) && ModuleSources.contains(module, ModuleSources.FilePath.of(file, basePath))
    }
}

/**
 * [searchScope] for the keys of [translationFile]: its module's code in a monorepo, the whole project
 * for a file in no module — a shared package's translations — or a project without modules.
 */
fun ModuleSources.usageScope(project: Project, translationFile: VirtualFile?): GlobalSearchScope =
    searchScope(project, ownerOf(project, translationFile))

/**
 * Every source of [project], or only [module]'s. A module whose translations all live outside
 * its root directory (a shared package) keeps the project-wide list, as key resolution does.
 */
fun ModuleSources.sourcesOf(project: Project, module: ModuleConfig?): List<LocalizationSource> {
    val all = project.service<LocalizationSourceService>().findAllSources(project)
    if (module == null) return all
    val basePath = project.basePath ?: ""
    val scoped = all.filter { source ->
        val file = (source.tree?.value() ?: source.host)?.containingFile?.virtualFile ?: return@filter false
        contains(module, ModuleSources.FilePath.of(file, basePath))
    }
    return scoped.ifEmpty { all }
}

/**
 * The JSON or YAML file behind [source], or null for any other format (PO, a JS object, …),
 * whose keys [com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys] does not read.
 *
 * Checked on the language id rather than on the PSI class: YAML is an optional dependency, and
 * `TranslationFileKeys.translationLeaves` only touches YAML classes for a file that is YAML.
 */
fun ModuleSources.readableFile(source: LocalizationSource): PsiFile? {
    val file = source.tree?.value()?.containingFile ?: return null
    return file.takeIf { it.language.isKindOf(JSON_LANGUAGE) || it.language.id == YAML_LANGUAGE }
}

private const val JSON_LANGUAGE = "JSON"
private const val YAML_LANGUAGE = "yaml"
