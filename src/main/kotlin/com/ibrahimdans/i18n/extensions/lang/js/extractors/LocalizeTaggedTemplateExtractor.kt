package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.extensions.technology.angulari18n.AngularI18nIds
import com.ibrahimdans.i18n.plugin.factory.ReferenceAssistant
import com.ibrahimdans.i18n.plugin.ide.references.code.ReferenceContributorBase
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.lang.javascript.psi.ecma6.ES6TaggedTemplateExpression
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext

/**
 * The custom id of an Angular message marked in code: `` $localize`:@@home.title:Welcome` ``, read from
 * the template's metadata block. The id is one key, never split at its dots; a `$localize` without a
 * custom id names none.
 */
class LocalizeTaggedTemplateExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean = idOf(element) != null

    override fun extract(element: PsiElement): RawKey = RawKey(listOf(KeyElement.literal(idOf(element).orEmpty())), unsplit = true)

    private fun idOf(element: PsiElement): String? {
        val template = element as? JSStringTemplateExpression ?: return null
        val tagged = template.parent as? ES6TaggedTemplateExpression ?: return null
        if (tagged.firstChild?.text != TAG) return null
        return AngularI18nIds.localizeIdOf(template.text)
    }

    private companion object {
        const val TAG = "\$localize"
    }
}

/**
 * Ctrl+click from a `$localize` id to its unit in the XLIFF files. Registered for JavaScript in
 * `plugin.xml`: the template is no call, so the call-based contributors match none.
 */
class LocalizeReferenceContributor : ReferenceContributorBase(LocalizeReferenceAssistant())

internal class LocalizeReferenceAssistant : ReferenceAssistant {

    private val extractor = LocalizeTaggedTemplateExtractor()

    override fun pattern(): ElementPattern<out PsiElement> =
        PlatformPatterns.psiElement(JSStringTemplateExpression::class.java).with(object : PatternCondition<JSStringTemplateExpression>("localizeId") {
            override fun accepts(element: JSStringTemplateExpression, context: ProcessingContext?) = extractor.canExtract(element)
        })

    override fun extractKey(element: PsiElement): FullKey? =
        element.takeIf(extractor::canExtract)?.let { RawKeyParser(it.project).parse(extractor.extract(it), it) }
}
