package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.NioWorkspace
import java.nio.file.Files
import java.nio.file.Path

/** What the `package.json` and `composer.json` files of a project require, those under `node_modules/` and `vendor/` left out. */
object ProjectPackages {

    const val ANGULAR = "@angular/core"
    val TRANSLOCO = listOf("@jsverse/transloco", "@ngneat/transloco")

    /** Laravel, or its translation component alone: what makes `__('messages.welcome')` a key, as the plugin's `LaravelProject` reads it. */
    val LARAVEL = listOf("laravel/framework", "illuminate/translation")

    /** Whether a `package.json` of [project] names one of [packages]. */
    fun requiresAny(project: Path, packages: List<String>): Boolean = manifestNames(project, "package.json", packages)

    /** Whether a `composer.json` of [project] names one of [packages]. */
    fun composerRequiresAny(project: Path, packages: List<String>): Boolean = manifestNames(project, "composer.json", packages)

    private fun manifestNames(project: Path, manifestName: String, packages: List<String>): Boolean =
        NioWorkspace(project).files(listOf("json"))
            .filter { it.file.fileName.toString() == manifestName }
            .any { manifest -> Files.readString(manifest.file).let { text -> packages.any { "\"$it\"" in text } } }
}
