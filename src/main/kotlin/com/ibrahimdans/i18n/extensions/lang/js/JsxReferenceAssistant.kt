package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.FormattedMessageExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.XmlAttributeKeyExtractor
import com.ibrahimdans.i18n.plugin.factory.ReferenceAssistant
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.XmlPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.util.ProcessingContext

/**
 * Navigation from the keys held by a JSX attribute: react-i18next's `<Trans i18nKey>` and
 * react-intl's `<FormattedMessage id>` — an `id` on any other tag (`<input id>`) is no key.
 */
class JsxReferenceAssistant: ReferenceAssistant {

    override fun pattern(): ElementPattern<out PsiElement> =
        XmlPatterns.psiElement(XmlAttributeValue::class.java).with(object : PatternCondition<XmlAttributeValue>("jsxKeyAttribute") {
            override fun accepts(element: XmlAttributeValue, context: ProcessingContext?) = extractorOf(element) != null
        })

    /** Parsed like the annotator parses it — a module's key template included — so both agree on the key. */
    override fun extractKey(element: PsiElement): FullKey? =
        extractorOf(element)?.let { RawKeyParser(element.project).parse(it.extract(element), element) }

    private fun extractorOf(element: PsiElement): KeyExtractor? =
        I18NEXT.takeIf { it.canExtract(element) } ?: activeExtractorOf(REACT_INTL, element)

    private companion object {
        val I18NEXT = XmlAttributeKeyExtractor()
        val REACT_INTL = listOf(FormattedMessageExtractor())
    }
}
