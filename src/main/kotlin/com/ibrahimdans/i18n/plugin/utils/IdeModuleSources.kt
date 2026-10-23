package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/** [file]'s path relative to [basePath] when it lives under it, absolute otherwise. */
fun ModuleSources.FilePath.Companion.of(file: VirtualFile, basePath: String): ModuleSources.FilePath =
    of(file.path, basePath)

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
