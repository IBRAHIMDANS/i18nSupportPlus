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
 * optional, and the extractor must not fail to load when it is absent. See [isPipeInput].
 */
class AngularTranslatePipeExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean = isPipeInput(element, PIPE)

    override fun extract(element: PsiElement): RawKey = RawKey(listOf(KeyElement.literal(pipeKeyOf(element))))
}

private val PIPE = Regex("""^[\s)]*\|\s*translate\b""")

/**
 * Whether [element] is a string literal written right before the pipe [pipe] matches: `'key' | translate`.
 *
 * Read on the text following the literal in one of its three closest ancestors: the pipe's left side,
 * the pipe expression, and one more for a parenthesised or ternary input. The ancestors' whole text is not enough:
 * in `[options]="{ type: 'date', label: 'validFrom' | translate }"`, the object holding `'date'`
 * contains the pipe too, and `'date'` was reported as a missing key.
 */
internal fun isPipeInput(element: PsiElement, pipe: Regex): Boolean {
    if (element !is JSLiteralExpression || !element.isStringLiteral) return false
    val end = element.textRange.endOffset
    return generateSequence(element.parent) { it.parent }
        .take(3)
        .any { ancestor -> pipe.containsMatchIn(ancestor.text.substring(end - ancestor.textRange.startOffset)) }
}

/**
 * The key of a pipe's string literal, its quotes removed. In an inline `template: '…'` quoted with
 * apostrophes, the injected fragment keeps the JavaScript escapes, so the literal reads `\'key\'` —
 * the form the extraction quick fix writes there, double quotes being taken by the attribute.
 */
internal fun pipeKeyOf(element: PsiElement): String {
    val text = element.text.trim()
    for (quote in listOf("\\'", "\\\"", "'", "\"")) {
        if (text.length >= 2 * quote.length && text.startsWith(quote) && text.endsWith(quote)) {
            return text.substring(quote.length, text.length - quote.length)
        }
    }
    return text
}
