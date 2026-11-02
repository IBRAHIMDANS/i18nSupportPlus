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
 * ([BladeMarkupTextExtractor]). The rules are those of [MarkupTexts]: an echo of a plain value —
 * `Hello {{ $user->name }}!` — is a variable of the message, written `:name` as Laravel does. A text
 * any other Blade construct splits — a raw echo `{!! … !!}`, a directive `@if(…)`, a comment, an echo
 * of a call — is left alone: what Blade inserts there is no value a message can name. Needs the Blade
 * plugin, without which a `.blade.php` file is PHP.
 */
class HardcodedBladeTextInspection :
    HardcodedMarkupTextInspection("I18nHardcodedBladeText", "inspection.hardcoded.jsx.text.message", BladeMarkup, BladeMarkupTextExtractor())

/**
 * The HTML of a Blade view: its echoes and directives are PHP the Blade plugin leaves out of the
 * markup, as template elements of the texts they split.
 */
internal object BladeMarkup : MarkupDialect {

    private const val LANGUAGE = "BladeHTML"

    /** An escaped echo, `{{ $name }}`, with the blanks the view writes after it: neither a comment `{{-- --}}` nor `{{{ }}}`. */
    private val ECHO = Regex("""\{\{(?![-{])(.*?)}}\s*""", RegexOption.DOT_MATCHES_ALL)

    /** A variable or a property of one: `$name`, `$user->name`. */
    private val PLAIN_VALUE = Regex("""\$[A-Za-z_]\w*(->[A-Za-z_]\w*)*""")

    /** A view's file is Blade, its markup the `BladeHTML` tree beside it: either one stands for the view. */
    override fun applies(element: PsiElement): Boolean =
        element.containingFile?.viewProvider?.languages.orEmpty().any { it.id == LANGUAGE } && !holdsBlade(element)

    /** An echo of a plain value beside the texts: the HTML tree leaves one ending a tag's content out of its text. */
    override fun isInterpolation(element: PsiElement): Boolean = isInnerInterpolation(element)

    /** An echo of a plain value: `{{ $user->name }}`. */
    override fun isInnerInterpolation(element: PsiElement): Boolean =
        element is OuterLanguageElement && expressionOf(element)?.let(PLAIN_VALUE::matches) == true

    override fun expressionOf(element: PsiElement): String? = ECHO.matchEntire(element.text)?.groupValues?.get(1)?.trim()

    /** The last segment of the value, without its `$`: `name` for `$user->name`. */
    override fun variableNameOf(expression: String): String? =
        if (PLAIN_VALUE.matches(expression)) expression.substringAfterLast("->").removePrefix("$") else null

    /** Laravel's: `:name`. */
    override fun placeholder(name: String): String = ":$name"

    /**
     * Whether Blade writes into the texts of [element]'s tag anything but an echo of a value, or
     * anything into the attribute value [element] is.
     */
    private fun holdsBlade(element: PsiElement): Boolean = when (element) {
        is XmlText -> PsiTreeUtil.getChildrenOfTypeAsList(element.parent ?: return false, XmlText::class.java)
            .any { text -> outers(text).any { !isInnerInterpolation(it) } }
        is XmlAttributeValue -> outers(element).isNotEmpty()
        else -> false
    }

    private fun outers(element: PsiElement): List<OuterLanguageElement> =
        PsiTreeUtil.getChildrenOfTypeAsList(element, OuterLanguageElement::class.java)
}

/**
 * Extracts a text of a Blade view — one [HardcodedBladeTextInspection] reports — into Laravel's
 * translation helper:
 *  - `<p>Save</p>` becomes `<p>{{ __('save') }}</p>`;
 *  - `<p>Hello {{ $user->name }}!</p>` becomes `<p>{{ __('hello', ['name' => $user->name]) }}</p>`,
 *    its message `Hello :name!`;
 *  - `placeholder="Name"` becomes `placeholder="{{ __('name') }}"`.
 */
internal class BladeMarkupTextExtractor : TranslationExtractor {

    override fun canExtract(element: PsiElement): Boolean = MarkupTexts.of(element, BladeMarkup) != null

    override fun isExtracted(element: PsiElement): Boolean = false

    override fun text(element: PsiElement): String = MarkupTexts.of(element, BladeMarkup)?.text.orEmpty()

    override fun textRange(element: PsiElement): TextRange = MarkupTexts.of(element, BladeMarkup)?.range ?: element.textRange

    override fun variables(element: PsiElement): List<MessageVariable> =
        (MarkupTexts.of(element, BladeMarkup) as? MarkupText.Content)?.variables.orEmpty()

    override fun template(element: PsiElement): (argument: String) -> String {
        val call = call(element)
        val variables = variables(element)
        return { call(it, variables) }
    }

    override fun call(element: PsiElement): (argument: String, variables: List<MessageVariable>) -> String {
        val attribute = (MarkupTexts.of(element, BladeMarkup) as? MarkupText.Attribute)?.attribute
        if (attribute != null) return { argument, _ -> "${attribute.name}=\"{{ __($argument) }}\"" }
        return { argument, variables ->
            if (variables.isEmpty()) "{{ __($argument) }}" else "{{ __($argument, ${replacements(variables)}) }}"
        }
    }

    /** Laravel's replacements: `['name' => $user->name]`. */
    private fun replacements(variables: List<MessageVariable>): String =
        variables.joinToString(", ", "[", "]") { "'${it.name}' => ${it.expression}" }
}
