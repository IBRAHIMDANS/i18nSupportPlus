package com.ibrahimdans.i18n.extensions.technology.transloco

import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.json.JsonFileType
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * Transloco's scopes: `src/assets/i18n/admin/en.json` next to the global `src/assets/i18n/en.json`
 * holds the scope `admin`, whose keys the code writes `admin.title`.
 *
 * Only in a Transloco project — a `package.json` outside `node_modules/` naming `@jsverse/transloco`
 * or `@ngneat/transloco`: elsewhere a `locales/admin/en.json` is no scope, and its first segment
 * must not be read as one.
 */
internal object TranslocoScopes {

    /** A scope file: the scope it holds and the locale it is written in. */
    data class ScopeFile(val scope: String, val locale: String, val file: VirtualFile)

    private val PACKAGES = listOf("\"@jsverse/transloco\"", "\"@ngneat/transloco\"")

    fun isTransloco(project: Project): Boolean {
        if (DumbService.isDumb(project)) return false
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val manifests = FilenameIndex.getVirtualFilesByName("package.json", GlobalSearchScope.projectScope(project))
                .filter { "/node_modules/" !in it.path }
            val transloco = manifests.any { manifest ->
                val text = runCatching { VfsUtilCore.loadText(manifest) }.getOrDefault("")
                PACKAGES.any { it in text }
            }
            CachedValueProvider.Result.create(transloco, *manifests.toTypedArray(), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
        }
    }

    /**
     * Every scope file of the project: a JSON file named after a locale, whose folder sits next to
     * global files named after a locale too. Depends on file names only, so it is kept until a file
     * is added, removed, renamed or moved.
     */
    fun scopeFiles(project: Project): List<ScopeFile> {
        if (!isTransloco(project)) return emptyList()
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val found = FileTypeIndex.getFiles(JsonFileType.INSTANCE, GlobalSearchScope.projectScope(project))
                .filter { "/node_modules/" !in it.path }
                .mapNotNull(::scopeFileOf)
                .sortedBy { it.file.path }
            CachedValueProvider.Result.create(found, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
        }
    }

    fun scopes(project: Project): Set<String> = scopeFiles(project).mapTo(mutableSetOf()) { it.scope }

    fun scopeOf(project: Project, file: VirtualFile): String? = scopeFiles(project).firstOrNull { it.file == file }?.scope

    private fun scopeFileOf(file: VirtualFile): ScopeFile? {
        val locale = file.nameWithoutExtension
        if (!LocalizationSourceService.looksLikeLocale(locale)) return null
        val scopeDir = file.parent ?: return null
        val root = scopeDir.parent ?: return null
        val hasGlobalFiles = root.children.any { !it.isDirectory && it.extension == "json" && LocalizationSourceService.looksLikeLocale(it.nameWithoutExtension) }
        return if (hasGlobalFiles) ScopeFile(scopeDir.name, locale, file) else null
    }
}
