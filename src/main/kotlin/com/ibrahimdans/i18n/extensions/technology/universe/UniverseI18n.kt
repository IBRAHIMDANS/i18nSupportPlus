package com.ibrahimdans.i18n.extensions.technology.universe

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * Meteor's universe:i18n, an Atmosphere package: it is declared in `.meteor/packages`, not in
 * `package.json`, so [com.ibrahimdans.i18n.plugin.ide.settings.FrameworkDetector] never sees it.
 *
 * Since its v2 the package ships no React hook. Its documentation has the application write its
 * own, `useTranslator(prefix)`, and projects often name theirs `useTranslation('Overview')`: the
 * argument is a key prefix, `t('help.caption')` reading `Overview.help.caption` from the one file
 * of each locale — not react-i18next's namespace, which names a file.
 */
object UniverseI18n {

    private const val PACKAGE = "universe:i18n"

    /** True when a `.meteor/packages` of [project] declares universe:i18n. False while indexing. */
    fun isUsedIn(project: Project): Boolean {
        if (DumbService.isDumb(project)) return false
        // Asked for every key literal: kept until a file moves or a packages file changes.
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val files = FilenameIndex.getVirtualFilesByName("packages", GlobalSearchScope.projectScope(project))
                .filter { it.parent?.name == ".meteor" }
            val used = files.any { file -> runCatching { VfsUtilCore.loadText(file) }.getOrNull()?.let(::declaresPackage) == true }
            CachedValueProvider.Result.create(used, *files.toTypedArray(), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
        }
    }

    /**
     * True when the text of a `.meteor/packages` file declares universe:i18n: one package per line,
     * with an optional version constraint (`universe:i18n@2.0.0`) and `#` comments.
     */
    fun declaresPackage(text: String): Boolean =
        text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .any { it == PACKAGE || it.startsWith("$PACKAGE@") }
}
