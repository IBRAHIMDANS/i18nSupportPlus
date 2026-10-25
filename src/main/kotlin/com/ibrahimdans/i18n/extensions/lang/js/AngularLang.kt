package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.AngularTranslatePipeExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.NgxTranslateDirectiveExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.TranslocoDirectiveExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.TranslocoPipeExtractor
import com.ibrahimdans.i18n.plugin.factory.ReferenceAssistant
import com.ibrahimdans.i18n.plugin.ide.completion.CompositeKeyCompletionContributor
import com.ibrahimdans.i18n.plugin.ide.references.code.ReferenceContributorBase
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText
import com.intellij.psi.xml.XmlToken
import com.intellij.util.ProcessingContext

/**
 * Angular templates: the call-based syntaxes of [JsLang] plus the `| translate` and `| transloco` pipes as they
 * actually appears once a template is parsed, and the attribute directives of both libraries.
 *
 * A `.html` is only parsed as Angular inside an Angular project — a component referencing it
 * through `templateUrl`, with `@angular/core` resolvable. Outside one it stays plain HTML and
 * nothing here matches.
 */
open class AngularLang : JsLang() {

    override fun syntaxOwnedExtractors(): List<KeyExtractor> = ANGULAR_EXTRACTORS + super.syntaxOwnedExtractors()

    private companion object {
        val ANGULAR_EXTRACTORS: List<KeyExtractor> = listOf(AngularTranslatePipeExtractor(), TranslocoPipeExtractor()) + DIRECTIVE_EXTRACTORS
    }
}

private val DIRECTIVE_EXTRACTORS: List<KeyExtractor> = listOf(TranslocoDirectiveExtractor(), NgxTranslateDirectiveExtractor())

/**
 * The key an Angular attribute directive takes: `<span transloco="key">`, its bound form
 * `<span [transloco]="'key'">` and — when [readsText] — the text of the element carrying the
 * directive without a value: ngx-translate's `<p translate>key</p>`.
 *
 * The directive is told apart on the attribute's name, with no compile-time dependency on the
 * Angular plugin, like the pipes. A value holding an interpolation is bound at runtime: no key.
 */
abstract class AngularDirectiveExtractor(private val directive: String, private val readsText: Boolean = false) : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean = keyOf(element) != null

    override fun extract(element: PsiElement): RawKey = RawKey(listOf(KeyElement.literal(keyOf(element).orEmpty())))

    /** Whether [value], written in the markup, can be a key. */
    protected open fun isKey(value: String): Boolean = value.isNotEmpty() && "{{" !in value

    private fun keyOf(element: PsiElement): String? = when (element) {
        is JSLiteralExpression -> element.takeIf { it.isQuotedLiteral && boundAttributeOf(it)?.name == "[$directive]" }?.stringValue
        is XmlAttributeValue -> (element.parent as? XmlAttribute)?.takeIf { it.name == directive }?.value?.trim()?.takeIf(::isKey)
        // The text is the key only when it is the element's whole content: `Hello {{ name }}` is
        // split into texts around the interpolation, and `Hello` alone is no key.
        is XmlText -> {
            val attribute = element.parentTag?.getAttribute(directive)
            element.text.trim().takeIf {
                readsText && attribute != null && attribute.value.isNullOrEmpty() && isWholeContent(element) && isKey(it)
            }
        }
        else -> null
    }

    /** Whether [text] is all its tag holds but the tag's own tokens: no interpolation, no child tag. */
    private fun isWholeContent(text: XmlText): Boolean =
        generateSequence(text.parent?.firstChild) { it.nextSibling }
            .none { it !== text && it !is XmlToken && it !is XmlAttribute && it !is PsiWhiteSpace }
}

/**
 * The attribute [literal] is the whole bound value of — `[transloco]="'key'"` — or null. The binding is
 * parsed in place by recent Angular plugins and injected by older ones: both are walked.
 */
private fun boundAttributeOf(literal: JSLiteralExpression): XmlAttribute? {
    val attribute = PsiTreeUtil.getParentOfType(literal, XmlAttribute::class.java)
        ?: InjectedLanguageManager.getInstance(literal.project).getInjectionHost(literal)
            ?.let { PsiTreeUtil.getParentOfType(it, XmlAttribute::class.java, false) }
    return attribute?.takeIf { it.value?.trim() == literal.text }
}

/**
 * Navigation from the attribute directives' keys to their translation. Registered for the template's
 * markup (`transloco="key"`) and for its expressions (`[transloco]="'key'"`) in `angularConfig.xml`:
 * no call holds them, so [JsReferenceContributor] matches none. A key written as the element's text,
 * `<p translate>key</p>`, gets none: a template's text is no host of contributed references.
 */
class AngularDirectiveReferenceContributor : ReferenceContributorBase(AngularDirectiveReferenceAssistant())

internal class AngularDirectiveReferenceAssistant : ReferenceAssistant {

    override fun pattern(): ElementPattern<out PsiElement> =
        PlatformPatterns.psiElement().with(object : PatternCondition<PsiElement>("angularDirectiveKey") {
            override fun accepts(element: PsiElement, context: ProcessingContext?) = DIRECTIVE_EXTRACTORS.any { it.canExtract(element) }
        })

    /** Parsed like the annotator parses it, so both agree on the key. */
    override fun extractKey(element: PsiElement): FullKey? =
        DIRECTIVE_EXTRACTORS.find { it.canExtract(element) }?.let { RawKeyParser(element.project).parse(it.extract(element), element) }
}

/**
 * Completion in Angular templates, with [AngularLang] — the pipes and directives included — rather
 * than [JsLang], which knows neither: `{{ '<caret>' | translate }}` offered nothing.
 */
class AngularCompletionContributor : CompositeKeyCompletionContributor(CaretAngularLang())

/**
 * [AngularLang] asked about the completion caret, which sits on a leaf token: the key of a directive
 * written in the markup is held by the [XmlAttributeValue] or [XmlText] above it.
 */
private class CaretAngularLang : AngularLang() {
    override fun extractRawKey(element: PsiElement): RawKey? =
        super.extractRawKey(element) ?: element.parent?.takeIf { it is XmlAttributeValue || it is XmlText }?.let { super.extractRawKey(it) }
}
