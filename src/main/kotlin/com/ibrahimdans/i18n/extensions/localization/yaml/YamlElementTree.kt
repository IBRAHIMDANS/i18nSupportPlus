package com.ibrahimdans.i18n.extensions.localization.yaml

import com.ibrahimdans.i18n.plugin.tree.Tree
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Tree wrapper around yaml psi tree
 */
class YamlElementTree(val element: PsiElement): Tree<PsiElement> {
    override fun value(): PsiElement = element
    override fun isTree(): Boolean = element is YAMLMapping || element is YAMLDocument

    private fun mapping(): YAMLMapping? =
        element as? YAMLMapping
            ?: PsiTreeUtil.getChildOfType(element as? YAMLDocument, YAMLMapping::class.java)

    override fun findChild(name: String): Tree<PsiElement>? =
        mapping()
            ?.getKeyValueByKey(name)
            ?.value
            ?.let(::YamlElementTree)

    override fun findChildren(prefix: String): List<Tree<PsiElement>> {
        return mapping()
            ?.keyValues
            ?.mapNotNull { kv -> kv.key?.takeIf { it.text.startsWith(prefix) }?.let(::YamlElementTree) }
            ?: emptyList()
    }
    companion object {
        /**
         * Creates YamlElementTree instance
         */
        fun create(file: PsiElement): YamlElementTree? {
            val fileRoot = PsiTreeUtil.getChildOfType(file, YAMLDocument::class.java) ?: return null
            return PsiTreeUtil.getChildOfType(fileRoot, YAMLMapping::class.java)?.let { YamlElementTree(it) }
                ?: YamlElementTree(fileRoot)
        }
    }
}