package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.AngularTranslatePipeExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.JsFoldingProvider
import com.ibrahimdans.i18n.extensions.lang.js.extractors.NgxTranslateDirectiveExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.TranslocoDirectiveExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.TranslocoPipeExtractor
import com.ibrahimdans.i18n.plugin.factory.FoldingProvider
import com.ibrahimdans.i18n.plugin.ide.folding.FoldingBuilderBase
import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText

/**
 * Folding in Angular templates: the calls [JsFoldingBuilder] folds, plus `{{ 'key' | translate }}`,
 * `{{ 'key' | transloco }}` and the attribute directives of both libraries — `<p translate>key</p>`,
 * `<p translate="key">`, `<span transloco="key">`, `<span [transloco]="'key'">`.
 *
 * [JsFoldingBuilder] carries [JsLang], which knows no pipe: registered on the template, it found
 * no key to fold. A `.html` template is also an `Angular17Html` file whose expressions sit in its
 * own tree, not in an injected `Angular2` fragment, so the builder declared on `Angular2` alone
 * never ran on it.
 *
 * The platform also hands this builder a component's inline template, the fragment injected into its
 * literal: the base class then places the regions on the fragment, where that pass wants them. The
 * same keys are folded from the component by [AngularInlineTemplateFoldingBuilder]; the editor keeps
 * one region per range.
 */
class AngularFoldingBuilder : FoldingBuilderBase(AngularFoldingLang(AngularFoldingProvider()))

/**
 * Folding of the pipes and directives of a component's inline `template: '…'`, declared on TypeScript.
 *
 * The template is an Angular fragment injected into the literal, and the platform folds a file with the
 * builders of its own language only: [AngularFoldingBuilder] never ran on it. [JsFoldingBuilder], which
 * does run on the component, already folds the calls of its injected fragments: only what it does not
 * know — pipes and directives — is collected here, so no key is folded twice.
 */
class AngularInlineTemplateFoldingBuilder : FoldingBuilderBase(AngularFoldingLang(AngularInlineTemplateFoldingProvider()))

private class AngularFoldingLang(private val provider: FoldingProvider) : AngularLang() {
    override fun foldingProvider(): FoldingProvider = provider
}

/**
 * [JsFoldingProvider] for the calls, the pipes and directives on top. A pipe folds whole, `'key' | translate`
 * into its value: the literal alone would leave `Home | translate` on screen. A directive folds its key alone,
 * the markup around it stays: `<p translate>Home</p>`.
 */
private class AngularFoldingProvider(private val js: FoldingProvider = JsFoldingProvider()) : FoldingProvider {

    override fun collectContainers(root: PsiElement): List<PsiElement> = (js.collectContainers(root) + angularKeys(root)).distinct()

    override fun collectLiterals(container: PsiElement): Pair<List<PsiElement>, Int> = js.collectLiterals(container)

    override fun getFoldingRange(container: PsiElement, offset: Int, psiElement: PsiElement): TextRange =
        angularRange(container, psiElement) ?: js.getFoldingRange(container, offset, psiElement)
}

/** The pipes and directives of the Angular fragments injected under the root: the inline templates. */
private class AngularInlineTemplateFoldingProvider : FoldingProvider {

    override fun collectContainers(root: PsiElement): List<PsiElement> {
        val injections = InjectedLanguageManager.getInstance(root.project)
        return PsiTreeUtil.findChildrenOfType(root, PsiLanguageInjectionHost::class.java)
            .flatMap { host -> injections.getInjectedPsiFiles(host).orEmpty().map { it.first } }
            .distinct()
            .filter { isAngularTemplate(it.language) }
            .flatMap(::angularKeys)
    }

    override fun collectLiterals(container: PsiElement): Pair<List<PsiElement>, Int> = Pair(listOf(container), 0)

    override fun getFoldingRange(container: PsiElement, offset: Int, psiElement: PsiElement): TextRange =
        angularRange(container, psiElement) ?: container.textRange
}

/** Angular's template dialects all derive from `Angular2Html`, named here with no dependency on the Angular plugin. */
private fun isAngularTemplate(language: Language): Boolean =
    generateSequence(language) { it.baseLanguage }.any { it.id == "Angular2Html" }

private val PIPES = listOf(AngularTranslatePipeExtractor(), TranslocoPipeExtractor())

/** Angular's own `i18n="@@id"` is no key of a translation file: only the libraries' directives fold. */
private val DIRECTIVES = listOf(TranslocoDirectiveExtractor(), NgxTranslateDirectiveExtractor())

/** The keys under [root] only Angular writes: on the left of a pipe, or held by a directive. */
private fun angularKeys(root: PsiElement): List<PsiElement> =
    PsiTreeUtil.findChildrenOfAnyType(root, JSLiteralExpression::class.java, XmlAttributeValue::class.java, XmlText::class.java)
        .filter { element -> PIPES.any { it.canExtract(element) } || DIRECTIVES.any { it.canExtract(element) } }

/**
 * The range [key] folds over, in the file the editor shows, or null when it is no pipe or directive key.
 * An inline `template: '…'` is a fragment injected into the component: its ranges are the fragment's.
 */
private fun angularRange(container: PsiElement, key: PsiElement): TextRange? {
    val range = pipeOf(key)?.textRange ?: directiveRange(key) ?: return null
    val injections = InjectedLanguageManager.getInstance(container.project)
    return if (injections.getTopLevelFile(container) !== container.containingFile) injections.injectedToHost(container, range)
    else range
}

/** The pipe expression [literal] is the left side of, as the extractors see it: within two ancestors. */
private fun pipeOf(literal: PsiElement): PsiElement? =
    if (PIPES.none { it.canExtract(literal) }) null
    else generateSequence(literal.parent) { it.parent }.take(2).firstOrNull { "|" in it.text }

/** The key text of a directive, quotes and surrounding blanks left out; a bound literal folds whole. */
private fun directiveRange(element: PsiElement): TextRange? {
    if (DIRECTIVES.none { it.canExtract(element) }) return null
    return when (element) {
        is XmlAttributeValue -> element.valueTextRange
        is XmlText -> {
            val text = element.text
            val start = element.textRange.startOffset + (text.length - text.trimStart().length)
            TextRange(start, start + text.trim().length)
        }
        else -> element.textRange
    }
}
