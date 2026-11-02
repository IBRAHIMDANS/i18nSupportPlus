package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.extensions.lang.js.HardcodedMarkupTextInspection
import com.ibrahimdans.i18n.extensions.lang.js.MarkupDialect
import com.ibrahimdans.i18n.extensions.lang.js.MarkupText
import com.ibrahimdans.i18n.extensions.lang.js.MarkupTexts
import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.templateLanguages.OuterLanguageElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText

/**
 * Flags a text written as is in the HTML of a Blade view — `<button>Save</button>`,
 * `<input placeholder="Name">` — with a quick fix writing Laravel's `{{ __('key') }}`
 * ([BladeMarkupTextExtractor]). The rules are those of [MarkupTexts]; besides, a text an echo or a
 * directive splits — `Hello {{ $name }}!`, `@if(…)` — is left alone: what Blade inserts is PHP, which
 * a message cannot hold as it is. Needs the Blade plugin, without which a `.blade.php` file is PHP.
 */
class HardcodedBladeTextInspection :
    HardcodedMarkupTextInspection("I18nHardcodedBladeText", "inspection.hardcoded.jsx.text.message", BladeMarkup, BladeMarkupTextExtractor())

/** The HTML of a Blade view: its echoes and directives are PHP the Blade plugin leaves out of the markup. */
internal object BladeMarkup : MarkupDialect {

    private const val LANGUAGE = "BladeHTML"

    /** A view's file is Blade, its markup the `BladeHTML` tree beside it: either one stands for the view. */
    override fun applies(element: PsiElement): Boolean =
        element.containingFile?.viewProvider?.languages.orEmpty().any { it.id == LANGUAGE } && !holdsBlade(element)

    /** An echo is PHP, not a path of the markup: never a variable of the message. */
    override fun isInterpolation(element: PsiElement): Boolean = false

    /** Laravel's: `:name`. */
    override fun placeholder(name: String): String = ":$name"

    /** Whether Blade writes into the texts of [element]'s tag, or into the attribute value [element] is. */
    private fun holdsBlade(element: PsiElement): Boolean = when (element) {
        is XmlText -> PsiTreeUtil.getChildrenOfTypeAsList(element.parent ?: return false, XmlText::class.java).any(::holdsOuter)
        is XmlAttributeValue -> holdsOuter(element)
        else -> false
    }

    private fun holdsOuter(element: PsiElement): Boolean = PsiTreeUtil.getChildOfType(element, OuterLanguageElement::class.java) != null
}

/**
 * Extracts a text of a Blade view — one [HardcodedBladeTextInspection] reports — into Laravel's
 * translation helper:
 *  - `<p>Save</p>` becomes `<p>{{ __('save') }}</p>`;
 *  - `placeholder="Name"` becomes `placeholder="{{ __('name') }}"`.
 */
internal class BladeMarkupTextExtractor : TranslationExtractor {

    override fun canExtract(element: PsiElement): Boolean = MarkupTexts.of(element, BladeMarkup) != null

    override fun isExtracted(element: PsiElement): Boolean = false

    override fun text(element: PsiElement): String = MarkupTexts.of(element, BladeMarkup)?.text.orEmpty()

    override fun textRange(element: PsiElement): TextRange = MarkupTexts.of(element, BladeMarkup)?.range ?: element.textRange

    override fun template(element: PsiElement): (argument: String) -> String {
        val call = call(element)
        return { call(it, emptyList()) }
    }

    override fun call(element: PsiElement): (argument: String, variables: List<MessageVariable>) -> String {
        val attribute = (MarkupTexts.of(element, BladeMarkup) as? MarkupText.Attribute)?.attribute
        if (attribute != null) return { argument, _ -> "${attribute.name}=\"{{ __($argument) }}\"" }
        return { argument, _ -> "{{ __($argument) }}" }
    }
}
