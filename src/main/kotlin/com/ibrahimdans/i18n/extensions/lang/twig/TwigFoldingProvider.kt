package com.ibrahimdans.i18n.extensions.lang.twig

import com.ibrahimdans.i18n.plugin.factory.FoldingProvider
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.jetbrains.twig.TwigTokenTypes

/**
 * Folds `{{ 'key'|trans }}` from the key's opening quote to the filter, and a whole
 * `{% trans %}key{% endtrans %}`, into the translation.
 */
internal class TwigFoldingProvider : FoldingProvider {

    override fun collectContainers(root: PsiElement): List<PsiElement> =
        PsiTreeUtil.collectElements(root) { it.firstChild == null && TwigTrans.keyOf(it) != null }.toList()

    override fun collectLiterals(container: PsiElement): Pair<List<PsiElement>, Int> = Pair(listOf(container), 0)

    override fun getFoldingRange(container: PsiElement, offset: Int, psiElement: PsiElement): TextRange {
        if (psiElement.elementType == TwigTokenTypes.TEMPLATE_HTML_TEXT) return psiElement.parent.textRange
        val open = psiElement.prevSibling ?: return psiElement.textRange
        val filterName = generateSequence(psiElement.nextSibling) { it.nextSibling }
            .firstOrNull { it.elementType == TwigTokenTypes.FILTER }
            ?.let { filter -> generateSequence(filter.nextSibling) { it.nextSibling }.firstOrNull { it.text.isNotBlank() } }
            ?: return psiElement.textRange
        return TextRange(open.textRange.startOffset, filterName.textRange.endOffset)
    }
}
