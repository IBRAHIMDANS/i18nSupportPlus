package com.ibrahimdans.i18n.extensions.localization.plain.`object`

import com.ibrahimdans.i18n.plugin.utils.type
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.at
import com.intellij.psi.PsiElement

/**
 * Plain text file wrapper
 */
class PlainObjectTextTree(val element: PsiElement): Tree<PsiElement> {

    override fun findChild(name: String): Tree<PsiElement>? {
        return element.children.find {
            it.type()=="SECTION" &&
                    it.children.at(0)?.let {
                        it.type()=="ID_LINE" && it.toString()==name
                    } == true
        }?.children?.at(0)?.let { PlainObjectTextTree(it) }
    }

    override fun isTree(): Boolean {
        return element == element.containingFile
    }

    override fun value(): PsiElement = element.nextSibling.nextSibling.let{it.children.at(0) ?: it}

    override fun findChildren(prefix: String): List<Tree<PsiElement>> = emptyList()
}
