package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.AngularTranslatePipeExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.JsFoldingProvider
import com.ibrahimdans.i18n.extensions.lang.js.extractors.TranslocoPipeExtractor
import com.ibrahimdans.i18n.plugin.factory.FoldingProvider
import com.ibrahimdans.i18n.plugin.ide.folding.FoldingBuilderBase
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Folding in Angular templates: the calls [JsFoldingBuilder] folds, plus `{{ 'key' | translate }}`
 * and `{{ 'key' | transloco }}`.
 *
 * [JsFoldingBuilder] carries [JsLang], which knows no pipe: registered on the template, it found
 * no key to fold. A `.html` template is also an `Angular17Html` file whose expressions sit in its
 * own tree, not in an injected `Angular2` fragment, so the builder declared on `Angular2` alone
 * never ran on it.
 */
class AngularFoldingBuilder : FoldingBuilderBase(AngularFoldingLang())

private class AngularFoldingLang : AngularLang() {
    override fun foldingProvider(): FoldingProvider = AngularFoldingProvider()
}

/**
 * [JsFoldingProvider] for the calls, the pipes on top. A pipe folds whole, `'key' | translate`
 * into its value: the literal alone would leave `Home | translate` on screen.
 */
private class AngularFoldingProvider(private val js: FoldingProvider = JsFoldingProvider()) : FoldingProvider {

    override fun collectContainers(root: PsiElement): List<PsiElement> =
        (js.collectContainers(root) + PsiTreeUtil.findChildrenOfType(root, JSLiteralExpression::class.java).filter(::isPipeKey)).distinct()

    override fun collectLiterals(container: PsiElement): Pair<List<PsiElement>, Int> = js.collectLiterals(container)

    override fun getFoldingRange(container: PsiElement, offset: Int, psiElement: PsiElement): TextRange {
        val pipe = pipeOf(psiElement) ?: return js.getFoldingRange(container, offset, psiElement)
        // An inline `template: '…'` is a fragment injected into the component: its ranges are the fragment's.
        val injections = InjectedLanguageManager.getInstance(container.project)
        return if (injections.getTopLevelFile(container) !== container.containingFile) injections.injectedToHost(container, pipe.textRange)
        else pipe.textRange
    }

    private fun isPipeKey(literal: PsiElement): Boolean = PIPES.any { it.canExtract(literal) }

    /** The pipe expression [literal] is the left side of, as the extractors see it: within two ancestors. */
    private fun pipeOf(literal: PsiElement): PsiElement? =
        if (!isPipeKey(literal)) null
        else generateSequence(literal.parent) { it.parent }.take(2).firstOrNull { "|" in it.text }

    private companion object {
        val PIPES = listOf(AngularTranslatePipeExtractor(), TranslocoPipeExtractor())
    }
}
