package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.technology.transloco.TranslocoScopes
import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag

/**
 * Flags a text written as is in an Angular template — `<button>Save</button>`,
 * `<input placeholder="Name">` — with a quick fix writing the pipe of the project's library
 * ([AngularTemplateTextExtractor]). The rules are those of [MarkupTexts]; besides:
 *  - only in a template the Angular plugin parses as one: a `.html` a component of an Angular
 *    project references, or a component's inline `template: '…'`, a fragment injected into it;
 *  - a tag carrying a translation directive holds a key, not a text — ngx-translate's
 *    `<p translate>key</p>`, Transloco's `transloco` — and one marked `i18n`, as an attribute
 *    marked `i18n-title`, is translated by Angular's own i18n.
 */
class HardcodedAngularTextInspection :
    HardcodedMarkupTextInspection("I18nHardcodedAngularText", "inspection.hardcoded.jsx.text.message", AngularMarkup, AngularTemplateTextExtractor())

/** The markup of an Angular template: `{{ … }}` interpolations, `@if (…) { … }` blocks, `{{name}}` placeholders. */
internal object AngularMarkup : MarkupDialect {

    private const val BASE_LANGUAGE = "Angular2Html"
    private val TRANSLATION_DIRECTIVES = setOf("translate", "[translate]", "transloco", "[transloco]", "i18n")

    override fun applies(element: PsiElement): Boolean =
        generateSequence(element.containingFile?.language) { it.baseLanguage }.any { it.id == BASE_LANGUAGE }

    override fun isInterpolation(element: PsiElement): Boolean = typeOf(element) == "NG:INTERPOLATION_EXPR"

    override fun isInterpolationBrace(element: PsiElement): Boolean =
        typeOf(element).let { it == "NG:INTERPOLATION_START" || it == "NG:INTERPOLATION_END" }

    override fun isIgnorable(element: PsiElement): Boolean =
        typeOf(element).let { it == "NG:BLOCK_START" || it == "NG:BLOCK_END" }

    /** ngx-translate's and Transloco's: `{{name}}`. */
    override fun placeholder(name: String): String = "{{$name}}"

    override fun holdsNoMessage(tag: XmlTag): Boolean = tag.attributes.any { it.name in TRANSLATION_DIRECTIVES }

    override fun translatesAttribute(tag: XmlTag, attribute: XmlAttribute): Boolean = tag.getAttribute("i18n-${attribute.name}") != null

    private fun typeOf(element: PsiElement): String = element.node?.elementType?.toString().orEmpty()
}

/**
 * Extracts a text of an Angular template — one [HardcodedAngularTextInspection] reports — into the
 * pipe of the project's library, `translate` (ngx-translate) or `transloco`:
 *  - `<p>Save</p>` becomes `<p>{{ 'save' | translate }}</p>`;
 *  - `<p>Hello {{ user.name }}!</p>` becomes `{{ 'hello' | translate: { name: user.name } }}`, its
 *    message `Hello {{name}}!`;
 *  - `placeholder="Name"` becomes `[placeholder]="'name' | translate"`.
 *
 * The library is the module's preset; without one, Transloco when the project depends on it
 * ([TranslocoScopes.isTransloco]), ngx-translate otherwise.
 */
internal class AngularTemplateTextExtractor : TranslationExtractor {

    override fun canExtract(element: PsiElement): Boolean = MarkupTexts.of(element, AngularMarkup) != null

    override fun isExtracted(element: PsiElement): Boolean = false

    override fun text(element: PsiElement): String = MarkupTexts.of(element, AngularMarkup)?.text.orEmpty()

    override fun textRange(element: PsiElement): TextRange = MarkupTexts.of(element, AngularMarkup)?.range ?: element.textRange

    override fun variables(element: PsiElement): List<MessageVariable> =
        (MarkupTexts.of(element, AngularMarkup) as? MarkupText.Content)?.variables.orEmpty()

    override fun template(element: PsiElement): (argument: String) -> String {
        val call = call(element)
        val variables = variables(element)
        return { call(it, variables) }
    }

    override fun call(element: PsiElement): (argument: String, variables: List<MessageVariable>) -> String {
        val pipe = pipeOf(element)
        val escape = hostEscaper(element)
        val attribute = (MarkupTexts.of(element, AngularMarkup) as? MarkupText.Attribute)?.attribute
        if (attribute != null) return { argument, _ -> escape("[${attribute.name}]=\"$argument | $pipe\"") }
        return { argument, variables ->
            val inputs = MessageVariables.inputs(variables)
            escape(if (inputs.isEmpty()) "{{ $argument | $pipe }}" else "{{ $argument | $pipe: $inputs }}")
        }
    }

    /**
     * How a call is written into the string literal holding an inline template: `'` escaped in
     * `template: '…'`, `"` in `template: "…"` — the call is written into the literal as is. A
     * template file, or a template literal (`` ` ``), takes it unchanged.
     */
    private fun hostEscaper(element: PsiElement): (String) -> String {
        val host = InjectedLanguageManager.getInstance(element.project).getInjectionHost(element) as? JSLiteralExpression
        val quote = host?.text?.firstOrNull()?.takeIf { it == '\'' || it == '"' } ?: return { it }
        return { call -> call.replace("\\", "\\\\").replace("$quote", "\\$quote") }
    }

    private fun pipeOf(element: PsiElement): String = when (ModulePresets.presetOf(element)) {
        TRANSLOCO -> TRANSLOCO
        NGX_TRANSLATE -> TRANSLATE
        else -> if (TranslocoScopes.isTransloco(element.project)) TRANSLOCO else TRANSLATE
    }

    private companion object {
        const val TRANSLOCO = "transloco"
        const val TRANSLATE = "translate"
        const val NGX_TRANSLATE = "ngx-translate"
    }
}
