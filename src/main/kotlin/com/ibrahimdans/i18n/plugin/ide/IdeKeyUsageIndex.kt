package com.ibrahimdans.i18n.plugin.ide

import com.ibrahimdans.i18n.IdeI18nRegistry
import com.ibrahimdans.i18n.plugin.KeyUsage
import com.ibrahimdans.i18n.plugin.KeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.inspection.IndirectKeyUsages
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationKeyUsages
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.settings.searchScope
import com.ibrahimdans.i18n.plugin.ide.toolwindow.DynamicKeyUsages
import com.ibrahimdans.i18n.plugin.utils.FallbackNamespaces
import com.ibrahimdans.i18n.plugin.utils.IdeWorkspace
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.PsiSearchHelper

/**
 * [KeyUsageIndex] on the IDE's word index: the text scan of [IndirectKeyUsages], its key-prefix
 * scan, and the dynamic keys of [DynamicKeyUsages] — the searches behind the table's *Usage*
 * column, asked here through one interface.
 *
 * The references the platform finds on a key's declaration are not part of it: they start from a
 * PSI element, not from a key. The callers holding one still ask [TranslationKeyUsages] for them.
 */
class IdeKeyUsageIndex(private val project: Project) : KeyUsageIndex {

    override fun usages(key: String): List<KeyUsage> =
        ReadAction.compute<List<KeyUsage>, RuntimeException> {
            val config = Settings.getInstance(project).config()
            val found = IndirectKeyUsages.textUsages(
                key, config, config.searchScope(project), PsiSearchHelper.getInstance(project), FallbackNamespaces.of(IdeWorkspace(project), config, IdeI18nRegistry),
            ) + IndirectKeyUsages.prefixedUsages(project, listOf(key), config)[key].orEmpty()
            found.mapNotNull(::usageOf)
        }

    override fun dynamicallyReached(keys: Collection<String>): Set<String> =
        ReadAction.compute<Set<String>, RuntimeException> {
            val config = Settings.getInstance(project).config()
            DynamicKeyUsages.reachedKeys(
                keys.toList(), config.searchScope(project), PsiSearchHelper.getInstance(project), config.nsSeparator, config.keySeparator,
            )
        }

    private fun usageOf(element: PsiElement): KeyUsage? {
        val path = element.containingFile?.virtualFile?.path ?: return null
        val range = element.textRange ?: return null
        return KeyUsage(path, TextSpan(range.startOffset, range.endOffset))
    }
}
