package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.PsiElement

/**
 * Extracts the key from `{{ 'key' | translate }}` inside a real Angular template.
 *
 * [NgxTranslatePipeExtractor] cannot serve here: it requires an `XmlText` or an
 * `XmlAttributeValue`, which is what the pipe looks like in JSX, where the markup is inert
 * text. An Angular template parses the same expression properly — the key arrives as a
 * `JSLiteralExpression` under an interpolation, in the `Angular2` dialect — so the XML
 * condition never holds and the key was never seen.
 *
 * The pipe is recognised on the surrounding text rather than through the Angular PSI classes,
 * which keeps this free of any compile-time dependency on the Angular plugin — the plugin is
 * optional, and the extractor must not fail to load when it is absent. Only the two closest
 * ancestors are examined, so an unrelated `| translate` elsewhere in the template does not
 * capture a literal that has nothing to do with it.
 */
class AngularTranslatePipeExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is JSLiteralExpression || !element.isStringLiteral) return false
        return generateSequence(element.parent) { it.parent }
            .take(2)
            .any { it.text.contains("|") && it.text.contains("translate") }
    }

    override fun extract(element: PsiElement): RawKey =
        RawKey(listOf(KeyElement.literal(element.text.trim().removeSurrounding("\"").removeSurrounding("'"))))
}
