package com.ibrahimdans.i18n.extensions.technology.symfony

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * A project translated with Symfony: a `composer.json` outside `vendor/` requires `symfony/translation`,
 * which `composer require translation` adds to an application.
 *
 * Its calls — `$translator->trans('key')`, `'key'|trans` — are read only there: `trans` is a common
 * method name, and Drupal's Twig `|trans` filter holds a text, not a key.
 */
internal object SymfonyProject {

    private const val TRANSLATION_PACKAGE = "\"symfony/translation\""

    /**
     * Kept until a `composer.json` changes or a file is added, removed or moved. Under a read action of
     * its own: a technology is asked for its sources from the tool window's background thread too.
     */
    fun isSymfony(project: Project): Boolean {
        if (DumbService.isDumb(project)) return false
        return runReadAction { cached(project) }
    }

    private fun cached(project: Project): Boolean =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val manifests = FilenameIndex.getVirtualFilesByName("composer.json", GlobalSearchScope.projectScope(project))
                .filter { "/vendor/" !in it.path }
            val symfony = manifests.any { manifest ->
                runCatching { VfsUtilCore.loadText(manifest) }.getOrDefault("").contains(TRANSLATION_PACKAGE)
            }
            CachedValueProvider.Result.create(symfony, *manifests.toTypedArray(), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
        }
}
