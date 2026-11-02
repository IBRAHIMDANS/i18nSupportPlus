package com.ibrahimdans.i18n.extensions.lang.twig

import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.plugin.factory.FoldingProvider
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.psi.PsiElement

/**
 * Twig templates of a Symfony project: the keys of `'key'|trans` and `{% trans %}key{% endtrans %}`
 * ([TwigTrans]), each looked up in the domain the template names — the key's namespace.
 *
 * No translation function is involved: the filter and the tag are Twig syntax, read whatever the
 * technologies publish. Extracting a text of a template into a key is not offered.
 */
class TwigLang : Lang {

    override fun canExtractKey(element: PsiElement, translationFunctionNames: List<String>): Boolean = TwigTrans.keyOf(element) != null

    override fun extractRawKey(element: PsiElement): RawKey? =
        TwigTrans.keyOf(element)?.let { RawKey(listOf(KeyElement.literal(it.text)), listOf(it.domain)) }

    override fun foldingProvider(): FoldingProvider = TwigFoldingProvider()

    override fun translationExtractor(): TranslationExtractor = NoExtraction

    override fun resolveLiteral(entry: PsiElement): PsiElement? = null

    /** Nothing of a template is offered for extraction. */
    private object NoExtraction : TranslationExtractor {
        override fun canExtract(element: PsiElement): Boolean = false
        override fun isExtracted(element: PsiElement): Boolean = false
        override fun text(element: PsiElement): String = ""
    }
}
