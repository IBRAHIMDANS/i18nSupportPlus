package com.ibrahimdans.i18n.extensions.localization.yaml

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Localization
import com.ibrahimdans.i18n.extensions.localization.vue.VueI18nBlockReader
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * `<i18n lang="yaml">`: the block is YAML, each top-level key a locale. Declared in
 * `vueYamlConfig.xml`, loaded only when both the Vue and the YAML plugins are.
 */
class VueI18nYamlBlockSources : VueI18nBlockReader() {

    override fun localization(): Localization<PsiElement>? =
        Extensions.LOCALIZATION.extensionList.firstOrNull { it is YamlLocalization }

    override fun localesOf(file: PsiFile): List<Pair<String, Tree<PsiElement>>> {
        val document = (file as? YAMLFile)?.documents?.firstOrNull() ?: return emptyList()
        val root = PsiTreeUtil.getChildOfType(document, YAMLMapping::class.java) ?: return emptyList()
        return root.keyValues.mapNotNull { locale ->
            (locale.value as? YAMLMapping)?.let { locale.keyText to YamlElementTree(it) }
        }
    }
}
