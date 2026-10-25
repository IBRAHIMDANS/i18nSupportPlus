package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.lang.php.extractors.PhpStringLiteralKeyExtractor
import com.ibrahimdans.i18n.plugin.factory.FoldingProvider
import com.ibrahimdans.i18n.plugin.utils.default
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/**
 * Folds the calls [PhpLang] reads a key from: `t('…')`, the GetText aliases, and in a Laravel project
 * `__()`, `trans()`, `trans_choice()` and the translator Blade's `@lang` compiles to. It used to fold
 * `t('…')` only, so a Laravel call showed no value.
 *
 * The key is read from the string token, as the annotator reads it, so a JSON phrase is folded whole.
 * In a Blade view the calls sit in the PHP fragments Blade injects — `@lang('…')`, `{{ __('…') }}` —
 * and are folded in the view.
 */
internal class PhpFoldingProvider: FoldingProvider {

    private val lang = PhpLang()

    override fun collectContainers(root: PsiElement): List<PsiElement> {
        // The platform also folds each injected fragment on its own; a Blade view's are folded from
        // the view instead (below), where the folds belong to the file shown.
        if (InjectedLanguageManager.getInstance(root.project).isInjectedFragment(root.containingFile)) return emptyList()
        val names = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
        // A PHP file holds its calls; a Blade view, in its injected fragments only — walked there
        // alone, so a PHP file's own injections (SQL, HTML) are never computed for folding.
        val roots = if (root.language.id == PHP_LANGUAGE) listOf(root) else injectedFiles(root)
        return roots.flatMap { PsiTreeUtil.findChildrenOfType(it, StringLiteralExpression::class.java) }
            .filter { literal -> keyToken(literal)?.let { lang.canExtractKey(it, names) } == true }
    }

    override fun collectLiterals(container: PsiElement): Pair<List<PsiElement>, Int> = Pair(listOfNotNull(keyToken(container)), 0)

    override fun getFoldingRange(container: PsiElement, offset: Int, psiElement: PsiElement): TextRange {
        val range = PsiTreeUtil.getParentOfType(psiElement, FunctionReference::class.java).default(psiElement).textRange
        val manager = InjectedLanguageManager.getInstance(container.project)
        if (!manager.isInjectedFragment(container.containingFile)) return range
        // Blade injects `@lang('key')` as `echo app('translator')->get('key')` around the argument it
        // holds: the call reaches past the host, and a fold must stay inside the node it is given.
        val host = manager.getInjectionHost(container)?.textRange ?: return range
        return manager.injectedToHost(container, range).intersection(host) ?: host
    }

    /** The PHP fragments injected under [root]: those of a Blade view. */
    private fun injectedFiles(root: PsiElement): List<PsiElement> {
        val manager = InjectedLanguageManager.getInstance(root.project)
        return PsiTreeUtil.findChildrenOfType(root, PsiLanguageInjectionHost::class.java)
            .flatMap { host -> manager.getInjectedPsiFiles(host).orEmpty().map { it.first } }
            .filter { it.language.id == PHP_LANGUAGE }
    }

    /** The token of [literal] holding its text: the literal itself in a single-quoted string, its contents in a double-quoted one. */
    private fun keyToken(literal: PsiElement): PsiElement? =
        generateSequence(literal.firstChild) { it.nextSibling }.firstOrNull(KEY_EXTRACTOR::canExtract)

    private companion object {
        val KEY_EXTRACTOR = PhpStringLiteralKeyExtractor()
        const val PHP_LANGUAGE = "PHP"
    }
}
