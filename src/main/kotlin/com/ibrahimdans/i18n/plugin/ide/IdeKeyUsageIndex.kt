package com.ibrahimdans.i18n.plugin.ide

import com.ibrahimdans.i18n.plugin.KeyUsage
import com.ibrahimdans.i18n.plugin.KeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.usages.CodeUsageIndexService
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.searchScope
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

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
     * for a caller looking at the code around a usage, which a [KeyUsage] does not hold. The index
     * keeps places: each is found again in its file, and [read] runs in that read action, while the
     * elements are valid.
     */
    fun <T : Any> readUsages(key: String, read: (PsiElement) -> T?): List<T> {
        val found = usages(key)
        return CodeUsageIndexService.readStep { index.elementsOf(found).distinct().mapNotNull(read) }
    }
}
