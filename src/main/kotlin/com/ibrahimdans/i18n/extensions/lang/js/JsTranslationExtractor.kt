package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.utils.type
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.Language
import com.intellij.lang.javascript.patterns.JSPatterns
import com.intellij.psi.PsiElement

internal class JsTranslationExtractor: TranslationExtractor {
    override fun canExtract(element: PsiElement): Boolean = "JS:STRING_LITERAL" == element.type()
            && element.containingFile.language.isKindOf(Language.findLanguageByID("JavaScript")!!)
    override fun isExtracted(element: PsiElement): Boolean = JSPatterns.jsArgument("t", 0).accepts(element.parent)
    override fun text(element: PsiElement): String = element.text.unQuote()
}