package com.ibrahimdans.i18n.extensions.lang.php

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * Laravel's translation helpers, recognised in PHP once [LaravelProject.isLaravel] says so.
 *
 * Only in a Laravel project: elsewhere `__('Hello', 'domain')` — WordPress — holds a text, not a
 * key, and every such call would be reported as an unresolved key.
 */
internal val LARAVEL_TRANSLATION_FUNCTIONS = listOf("__", "trans", "trans_choice")

internal object LaravelProject {

    private val LARAVEL_PACKAGES = listOf("\"laravel/framework\"", "\"illuminate/translation\"")

    /**
     * Whether a `composer.json` of the project, outside `vendor/`, names Laravel or its translation
     * component. Kept until a `composer.json` changes or a file is added, removed or moved.
     */
    fun isLaravel(project: Project): Boolean {
        if (DumbService.isDumb(project)) return false
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val manifests = FilenameIndex.getVirtualFilesByName("composer.json", GlobalSearchScope.projectScope(project))
                .filter { "/vendor/" !in it.path }
            val laravel = manifests.any { manifest ->
                val text = runCatching { VfsUtilCore.loadText(manifest) }.getOrDefault("")
                LARAVEL_PACKAGES.any { it in text }
            }
            CachedValueProvider.Result.create(laravel, *manifests.toTypedArray(), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
        }
    }
}
