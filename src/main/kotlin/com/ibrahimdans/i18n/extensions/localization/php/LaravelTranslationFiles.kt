package com.ibrahimdans.i18n.extensions.localization.php

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.Technology
import com.ibrahimdans.i18n.extensions.lang.php.LaravelProject
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.jetbrains.php.lang.psi.PhpFile

/**
 * Laravel names a key of `lang/en/messages.php` `messages.welcome`: the file name leads, followed by
 * the *key* separator. Each such file name is declared a key scope, so that a key whose first
 * segment names one is read with it as its namespace — any other key is parsed as before — and the
 * keys of the file are searched for in the code under that spelling.
 *
 * A package's override (`lang/vendor/courier/en/messages.php`) declares `courier::messages`, the
 * namespace Laravel reads `__('courier::messages.welcome')` from.
 *
 * A package without an override declares nothing, by choice: `__('mailer::messages.welcome')` is
 * not read as a key, so it is neither resolved nor reported. The package ships its translations
 * under Composer's `vendor/` and registers them at runtime (`loadTranslationsFrom()`): the project
 * holds no file to check such a key against, and reporting it would flag every working call.
 *
 * Only in a Laravel project ([LaravelProject.isLaravel]); declares no call and no source of its own.
 */
class LaravelTranslationFiles : Technology {

    override fun translationFunctionNames(): List<String> = emptyList()
    override fun findSourcesByConfiguration(project: Project): List<LocalizationSource> = emptyList()
    override fun initialize(project: Project) {}
    override fun cfgNamespaces(): List<String> = emptyList()

    /** The `laravel` preset of a module: its layout is the one this technology reads. */
    override fun frameworkId(): String = "laravel"

    override fun keyScopes(project: Project): Set<String> =
        if (LaravelProject.isLaravel(project)) scopeFiles(project).values.toSet() else emptySet()

    override fun keyScopeOf(file: PsiFile): String? {
        if (file !is PhpFile || !LaravelProject.isLaravel(file.project)) return null
        val virtualFile = file.originalFile.virtualFile ?: return null
        return scopeFiles(file.project)[virtualFile]
    }

    /**
     * The PHP translation files of the project, with the scope each one names. Asked for every key
     * parsed: recomputed only when the scan hands out a new list, which it keeps while nothing moves.
     */
    private fun scopeFiles(project: Project): Map<VirtualFile, String> {
        val sources = project.service<LocalizationSourceService>().findAllSources(project)
        cache?.takeIf { it.first === sources }?.let { return it.second }
        val scopes = sources
            .filter { it.localization is PhpArrayLocalization && it.tree != null }
            .mapNotNull { source ->
                val file = source.tree?.value()?.containingFile?.virtualFile ?: return@mapNotNull null
                PhpArrayLocalization.namespaceOf(file)?.let { file to it }
            }
            .toMap()
        cache = sources to scopes
        return scopes
    }

    @Volatile
    private var cache: Pair<List<LocalizationSource>, Map<VirtualFile, String>>? = null
}
