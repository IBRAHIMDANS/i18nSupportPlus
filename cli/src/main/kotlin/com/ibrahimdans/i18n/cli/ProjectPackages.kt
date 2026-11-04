package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.NioWorkspace
import java.nio.file.Path

/**
 * What the `package.json` and `composer.json` files of a project require, those under `node_modules/` and `vendor/`
 * left out. A manifest that is not UTF-8 stops the check, naming it ([TextFiles.read]).
 */
object ProjectPackages {

    const val ANGULAR = "@angular/core"
    val TRANSLOCO = listOf("@jsverse/transloco", "@ngneat/transloco")

    /** Laravel, or its translation component alone: what makes `__('messages.welcome')` a key, as the plugin's `LaravelProject` reads it. */
    val LARAVEL = listOf("laravel/framework", "illuminate/translation")

    /** Whether a `package.json` of [project] names one of [packages]. */
    fun requiresAny(project: Path, packages: List<String>): Boolean = manifestNames(project, "package.json", packages)

    /** Whether a `composer.json` of [project] names one of [packages]. */
    fun composerRequiresAny(project: Path, packages: List<String>): Boolean = manifestNames(project, "composer.json", packages)

    private fun manifestNames(project: Path, manifestName: String, packages: List<String>): Boolean {
        // The workspace walks the absolute root: a relative [project] could not relativize its files.
        val root = project.toAbsolutePath().normalize()
        return NioWorkspace(root).files(listOf("json"))
            .filter { it.file.fileName.toString() == manifestName }
            .any { manifest -> TextFiles.read(manifest.file, root.relativize(manifest.file).toString()).let { text -> packages.any { "\"$it\"" in text } } }
    }
}
