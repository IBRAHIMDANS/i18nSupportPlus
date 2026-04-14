package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.LinguiTransKeyExtractor
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

class JsxLang : JsLang() {

    override fun translationExtractor(): TranslationExtractor = JsxTranslationExtractor()

    override fun extractRawKey(element: PsiElement): RawKey? {
        val transExtractor = LinguiTransKeyExtractor()
        if (transExtractor.canExtract(element)) return transExtractor.extract(element)
        return super.extractRawKey(element)
    }

    override fun canExtractKey(element: PsiElement, translationFunctionNames: List<String>): Boolean {
        if (isInsideLinguiTrans(element) && LinguiTransKeyExtractor().canExtract(element)) return true
        return super.canExtractKey(element, translationFunctionNames)
    }

    private fun isInsideLinguiTrans(element: PsiElement): Boolean {
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java) ?: return false
        return tag.name == "Trans"
    }
}
