package com.ibrahimdans.i18n.plugin.ide

import com.ibrahimdans.i18n.IdeI18nRegistry
import com.ibrahimdans.i18n.plugin.KeyUsage
import com.ibrahimdans.i18n.plugin.KeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.inspection.IndirectKeyUsages
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationKeyUsages
import com.ibrahimdans.i18n.plugin.ide.inspection.isIn
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.DynamicKeyUsages
import com.ibrahimdans.i18n.plugin.utils.FallbackNamespaces
import com.ibrahimdans.i18n.plugin.utils.IdeWorkspace
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.searchScope
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper

/**
 * [KeyUsageIndex] on the IDE's word index: the text scan of [IndirectKeyUsages], its key-prefix
 * scan, and the dynamic keys of [DynamicKeyUsages] — the searches behind the table's *Usage*
 * column, asked here through one interface.
 *
 * The references the platform finds on a key's declaration are not part of it: they start from a
 * PSI element, not from a key. The callers holding one still ask [TranslationKeyUsages] for them.
 *
 * With a [module], only its code is searched ([ModuleSources.searchScope]): in a monorepo, a key of
 * `apps/web` that `apps/admin` alone uses is unused.
 */
class IdeKeyUsageIndex(private val project: Project, private val module: ModuleConfig? = null) : KeyUsageIndex {

    override fun usages(key: String): List<KeyUsage> =
        usages(listOf(key))[key].orEmpty()

    override fun usages(keys: Collection<String>): Map<String, List<KeyUsage>> =
        search(keys) { unique, scope ->
            val prefixed = scope.prefixedUsages(unique)
            unique.associateWith { key ->
                (scope.textUsages(key) + prefixed[key].orEmpty()).mapNotNull(::usageOf).distinct()
            }
        }

    override fun usageCounts(keys: Collection<String>): Map<String, Int> =
        search(keys) { unique, scope ->
            val prefixed = scope.prefixedUsages(unique)
            unique.associateWith { key -> scope.textCount(key) + prefixed[key].orEmpty().size }
        }

    /** Runs [query] in one read action, on the distinct [keys], with what the batch's text searches share. */
    private fun <T> search(keys: Collection<String>, query: (List<String>, TextSearch) -> T): T =
        ReadAction.compute<T, RuntimeException> {
            val config = Settings.getInstance(project).config()
            val scope = TextSearch(
                project, config, ModuleSources.searchScope(project, module), PsiSearchHelper.getInstance(project),
                FallbackNamespaces.of(IdeWorkspace(project), config, IdeI18nRegistry),
            )
            query(keys.distinct(), scope)
        }

    private class TextSearch(
        private val project: Project,
        val config: Config,
        private val scope: GlobalSearchScope,
        private val helper: PsiSearchHelper,
        private val fallbacks: List<String>,
    ) {
        fun textUsages(key: String) = IndirectKeyUsages.textUsages(key, config, scope, helper, fallbacks)
        fun textCount(key: String) = IndirectKeyUsages.textCount(key, config, scope, helper, fallbacks)

        /** The key-prefix scan, kept to [scope]: it searches the whole project itself. */
        fun prefixedUsages(keys: List<String>): Map<String, List<PsiElement>> =
            IndirectKeyUsages.prefixedUsages(project, keys, config).mapValues { (_, found) -> found.filter { it.isIn(scope) } }
    }

    override fun dynamicallyReached(keys: Collection<String>): Set<String> =
        ReadAction.compute<Set<String>, RuntimeException> {
            val config = Settings.getInstance(project).config()
            DynamicKeyUsages.reachedKeys(
                keys.toList(), ModuleSources.searchScope(project, module), PsiSearchHelper.getInstance(project), config.nsSeparator, config.keySeparator,
            )
        }

    private fun usageOf(element: PsiElement): KeyUsage? {
        val path = element.containingFile?.virtualFile?.path ?: return null
        val range = element.textRange ?: return null
        return KeyUsage(path, TextSpan(range.startOffset, range.endOffset))
    }
}
