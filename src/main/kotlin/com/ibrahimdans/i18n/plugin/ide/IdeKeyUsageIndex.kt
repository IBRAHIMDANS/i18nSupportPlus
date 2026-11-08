package com.ibrahimdans.i18n.plugin.ide

import com.ibrahimdans.i18n.IdeI18nRegistry
import com.ibrahimdans.i18n.plugin.KeyUsage
import com.ibrahimdans.i18n.plugin.KeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.inspection.IndirectKeyUsages
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationKeyUsages
import com.ibrahimdans.i18n.plugin.ide.inspection.isIn
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.usages.CodeUsageIndexService
import com.ibrahimdans.i18n.plugin.utils.FallbackNamespaces
import com.ibrahimdans.i18n.plugin.utils.IdeWorkspace
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.searchScope
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.PsiSearchHelper

/**
 * [KeyUsageIndex] on the project's code, read once by [CodeUsageIndexService]: the call sites naming a
 * key, with its namespace or under a hook declaring it, under a hook's key prefix or context, the
 * translation values linking to it, and the keys built at runtime — the answers behind the table's
 * *Usage* column, for every key at once.
 *
 * The references the platform finds on a key's declaration are not part of it: they start from a
 * PSI element, not from a key. The callers holding one still ask [TranslationKeyUsages] for them.
 *
 * With a [module], only its code is searched ([ModuleSources.searchScope]): in a monorepo, a key of
 * `apps/web` that `apps/admin` alone uses is unused.
 */
class IdeKeyUsageIndex(private val project: Project, private val module: ModuleConfig? = null) : KeyUsageIndex {

    private val index get() = CodeUsageIndexService.getInstance(project)

    override fun usages(key: String): List<KeyUsage> =
        usages(listOf(key))[key].orEmpty()

    override fun usages(keys: Collection<String>): Map<String, List<KeyUsage>> =
        index.usages(keys, module).mapValues { (_, found) -> found.all }

    override fun usageCounts(keys: Collection<String>): Map<String, Int> =
        index.usages(keys, module).mapValues { (_, found) -> found.count }

    override fun dynamicallyReached(keys: Collection<String>): Set<String> =
        index.dynamicallyReached(keys, module)

    /**
     * What [read] makes of each element behind [key]'s [usages], those it reads as nothing left out:
     * for a caller looking at the code around a usage, which a [KeyUsage] no longer holds. [read]
     * runs in the search's read action, while the elements are valid. One key, searched for itself:
     * the index keeps places, not elements.
     */
    fun <T : Any> readUsages(key: String, read: (PsiElement) -> T?): List<T> = CodeUsageIndexService.readStep {
        val config = Settings.getInstance(project).config()
        val scope = ModuleSources.searchScope(project, module)
        val fallbacks = FallbackNamespaces.of(IdeWorkspace(project), config, IdeI18nRegistry)
        val texts = IndirectKeyUsages.textUsages(key, config, scope, PsiSearchHelper.getInstance(project), fallbacks)
        val prefixed = IndirectKeyUsages.prefixedUsages(project, listOf(key), config)[key].orEmpty().filter { it.isIn(scope) }
        (texts + prefixed).distinct().mapNotNull(read)
    }
}
