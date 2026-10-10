package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.VueI18nKeypathExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.VueTDirectiveExtractor
import com.ibrahimdans.i18n.plugin.factory.ReferenceAssistant
import com.ibrahimdans.i18n.plugin.ide.references.code.ReferenceContributorBase
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PatternCondition
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext

/**
 * Navigation from vue-i18n's template keys — `<i18n-t keypath>` and `v-t` — to their translation.
 * Registered for Vue (the attribute) and VueJS (the directive's literal) in `vueConfig.xml`.
 */
class VueI18nMarkupReferenceContributor : ReferenceContributorBase(VueI18nMarkupReferenceAssistant())

internal class VueI18nMarkupReferenceAssistant : ReferenceAssistant {

    private val extractors = listOf(VueI18nKeypathExtractor(), VueTDirectiveExtractor())

    override fun pattern(): ElementPattern<out PsiElement> =
        PlatformPatterns.psiElement().with(object : PatternCondition<PsiElement>("vueI18nMarkupKey") {
            override fun accepts(element: PsiElement, context: ProcessingContext?) = extractors.any { it.canExtract(element) }
        })

    /** Parsed like the annotator parses it, so both agree on the key. */
    override fun extractKey(element: PsiElement): FullKey? =
        extractors.find { it.canExtract(element) }?.let { RawKeyParser(element.project).parse(it.extract(element), element) }
}
