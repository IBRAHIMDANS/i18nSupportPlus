package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.intellij.psi.xml.XmlAttributeValue
import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.lang.js.isDirectOrConfiguredCall
import com.ibrahimdans.i18n.plugin.factory.FoldingProvider
import com.ibrahimdans.i18n.plugin.utils.default
import com.intellij.lang.javascript.patterns.JSPatterns
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSSpreadExpression
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil

internal class JsFoldingProvider: FoldingProvider {

    private companion object {
        val I18N_KEY_ATTRIBUTE = XmlAttributeKeyExtractor()
    }

    private fun isI18nArgument(element: PsiElement): Boolean {
        if (!JSPatterns.jsArgument("t", 0).accepts(element) &&
            !JSPatterns.jsArgument("\$t", 0).accepts(element)) return false
        return isDirectOrConfiguredCall(element)
    }

    private fun isDirectOrConfiguredCall(element: PsiElement): Boolean =
        isDirectOrConfiguredCall(element, Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() })

    /**
     * Returns true if [element] is nested inside a JSSpreadExpression before reaching
     * the argument list. Such literals cannot be static i18n keys and cause duplicate
     * folding descriptors (e.g. t(...args: 'ns:key') repeats the folded text N times).
     */
    private fun isInsideSpreadArgument(element: PsiElement): Boolean {
        var current = element.parent
        while (current != null) {
            if (current is JSSpreadExpression) return true
            if (current is JSCallExpression) return false
            current = current.parent
        }
        return false
    }

    override fun collectContainers(root: PsiElement): List<PsiElement> {
        // Direct JS nodes (standard .js/.ts/.jsx/.tsx files), and the `i18nKey` attributes of
        // react-i18next's `<Trans>`, which no call holds: they used to be left unfolded.
        val direct = PsiTreeUtil
            .findChildrenOfType(root, JSLiteralExpression::class.java)
            .filter { isI18nArgument(it) && !isInsideSpreadArgument(it) } +
            PsiTreeUtil.findChildrenOfType(root, XmlAttributeValue::class.java).filter(I18N_KEY_ATTRIBUTE::canExtract)

        if (direct.isNotEmpty()) return direct

        // Injected language fragments (Vue <template>, etc.)
        val result = mutableListOf<PsiElement>()
        val manager = InjectedLanguageManager.getInstance(root.project)
        root.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                if (element is PsiLanguageInjectionHost) {
                    manager.getInjectedPsiFiles(element)?.forEach { pair ->
                        PsiTreeUtil.findChildrenOfType(pair.first, JSLiteralExpression::class.java)
                            .filter { isI18nArgument(it) && !isInsideSpreadArgument(it) }
                            .forEach { result.add(it) }
                    }
                }
            }
        })

        return result
    }

    override fun collectLiterals(container: PsiElement): Pair<List<PsiElement>, Int> = Pair(listOf(container), 0)

    override fun getFoldingRange(container: PsiElement, offset: Int, psiElement: PsiElement): TextRange {
        val callExpr = PsiTreeUtil.getParentOfType(psiElement, JSCallExpression::class.java)
        val sourceElement = callExpr ?: psiElement

        // Check if element is in an injected fragment
        val manager = InjectedLanguageManager.getInstance(container.project)
        val topLevelFile = manager.getTopLevelFile(container)
        if (topLevelFile !== container.containingFile) {
            return manager.injectedToHost(container, sourceElement.textRange)
        }

        return sourceElement.textRange
    }
}
