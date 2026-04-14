package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.LinguiTransKeyExtractor
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.intellij.psi.PsiElement

class JsxLang : JsLang() {

    companion object {
        private val LINGUI_TRANS_EXTRACTOR = LinguiTransKeyExtractor()
    }

    override fun translationExtractor(): TranslationExtractor = JsxTranslationExtractor()

    override fun extractRawKey(element: PsiElement): RawKey? {
        if (LINGUI_TRANS_EXTRACTOR.canExtract(element)) return LINGUI_TRANS_EXTRACTOR.extract(element)
        return super.extractRawKey(element)
    }

    override fun canExtractKey(element: PsiElement, translationFunctionNames: List<String>): Boolean {
        if (LINGUI_TRANS_EXTRACTOR.canExtract(element)) return true
        return super.canExtractKey(element, translationFunctionNames)
    }
}
