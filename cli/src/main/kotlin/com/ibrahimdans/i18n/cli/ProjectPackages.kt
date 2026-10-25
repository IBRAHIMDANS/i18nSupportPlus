package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.NioWorkspace
import java.nio.file.Files
import java.nio.file.Path

/** What the `package.json` files of a project require, those under `node_modules/` left out. */
object ProjectPackages {

    const val ANGULAR = "@angular/core"
    val TRANSLOCO = listOf("@jsverse/transloco", "@ngneat/transloco")

    /** Whether a `package.json` of [project] names one of [packages]. */
    fun requiresAny(project: Path, packages: List<String>): Boolean =
        NioWorkspace(project).files(listOf("json"))
            .filter { it.file.fileName.toString() == "package.json" }
            .any { manifest -> Files.readString(manifest.file).let { text -> packages.any { "\"$it\"" in text } } }
}
