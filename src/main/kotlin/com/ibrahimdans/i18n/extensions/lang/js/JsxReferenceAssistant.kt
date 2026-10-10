package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.XmlAttributeKeyExtractor
import com.ibrahimdans.i18n.plugin.factory.ReferenceAssistant
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.XmlPatterns
import com.intellij.psi.PsiElement

class JsxReferenceAssistant: ReferenceAssistant {

    override fun pattern(): ElementPattern<out PsiElement> {
        return XmlPatterns.xmlAttributeValue("i18nKey")
    }
    /** Parsed like the annotator parses it — a module's key template included — so both agree on the key. */
    override fun extractKey(element: PsiElement): FullKey? =
        EXTRACTOR.takeIf { it.canExtract(element) }?.let { RawKeyParser(element.project).parse(it.extract(element), element) }

    private companion object {
        val EXTRACTOR = XmlAttributeKeyExtractor()
    }
}