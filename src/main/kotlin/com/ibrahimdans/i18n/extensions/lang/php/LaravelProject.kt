package com.ibrahimdans.i18n.extensions.lang.php

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.ParameterList

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

private val TRANSLATOR_METHODS = setOf("get", "choice")
private val TRANSLATORS = setOf("app('translator')", "app(\"translator\")", "Lang", "\\Lang", "\\Illuminate\\Support\\Facades\\Lang")

/**
 * True when [element] — a string token or the expression holding it — is the first argument of
 * Laravel's translator: `app('translator')->get('key')`, `->choice('key', n)`, or the `Lang` facade.
 *
 * Blade's `@lang('key')` and `@choice('key', n)` reach the PHP side exactly so: the Blade plugin
 * injects them as `echo app('translator')->get('key')` and `->choice('key', n)`. The object called
 * is checked, not only the method: `$request->get('id')` is no translation call.
 */
internal fun isLaravelTranslatorCall(element: PsiElement): Boolean {
    val argument = generateSequence(element) { it.parent }.firstOrNull { it.parent is ParameterList } ?: return false
    val call = argument.parent.parent as? MethodReference ?: return false
    if (call.name !in TRANSLATOR_METHODS) return false
    if (call.parameters.firstOrNull() !== argument) return false
    val translator = call.classReference?.text?.filterNot { it.isWhitespace() } ?: return false
    return translator in TRANSLATORS
}

