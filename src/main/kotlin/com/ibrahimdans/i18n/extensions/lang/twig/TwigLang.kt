package com.ibrahimdans.i18n.extensions.lang.twig

import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.plugin.factory.FoldingProvider
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference

/**
 * Twig templates of a Symfony project: the keys of `'key'|trans` and `{% trans %}key{% endtrans %}`
 * ([TwigTrans]), each looked up in the domain the template names — the key's namespace.
 *
 * No translation function is involved: the filter and the tag are Twig syntax, read whatever the
 * technologies publish. A text of the template's HTML is extracted into `{{ 'key'|trans }}` ([TwigMarkupTextExtractor]).
 */
class TwigLang : Lang {

    override fun canExtractKey(element: PsiElement, translationFunctionNames: List<String>): Boolean = TwigTrans.keyOf(element) != null

    override fun extractRawKey(element: PsiElement): RawKey? =
        TwigTrans.keyOf(element)?.let { RawKey(listOf(KeyElement.literal(it.text)), listOf(it.domain)) }

    override fun foldingProvider(): FoldingProvider = TwigFoldingProvider()

    override fun translationExtractor(): TranslationExtractor = TwigMarkupTextExtractor()

    /** A key of the template: its token asks no contributor, the reference is built here. */
    override fun references(literal: PsiElement): List<PsiReference> =
        if (TwigTrans.keyOf(literal) != null) TwigKeyReferences.of(literal, literal) else literal.references.toList()

    /** A key of the template found by a text search — what usage counts and *Unused key* add up. */
    override fun resolveLiteral(entry: PsiElement): PsiElement? = entry.takeIf { TwigTrans.keyOf(it) != null }
}
