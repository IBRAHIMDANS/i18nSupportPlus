package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.technology.angulari18n.AngularI18nMarkers
import com.ibrahimdans.i18n.extensions.technology.transloco.TranslocoScopes
import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlTokenType

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
 * ([TranslocoScopes.isTransloco]), ngx-translate otherwise. With Angular's own i18n — its preset, or an
 * `angular.json` listing translations — the text is marked instead, `<p i18n="@@save">Save</p>`
 * ([AngularI18nMarkers]). Its interpolations then stay in the template, and the message keeps them as
 * written, `Hello {{ user.name }}!`: the XLIFF file receives them as the placeholders `ng extract-i18n`
 * writes (`XliffContentGenerator`).
 */
internal class AngularTemplateTextExtractor : TranslationExtractor {

    override fun canExtract(element: PsiElement): Boolean = MarkupTexts.of(element, AngularMarkup) != null

    override fun isExtracted(element: PsiElement): Boolean = false

    override fun text(element: PsiElement): String {
        val text = MarkupTexts.of(element, AngularMarkup) ?: return ""
        return nativeContent(element, text)?.let(::nativeMessage) ?: text.text
    }

    override fun textRange(element: PsiElement): TextRange {
        val text = MarkupTexts.of(element, AngularMarkup) ?: return element.textRange
        return if (marksNatively(element)) AngularI18nMarkers.of(text).range else text.range
    }

    /**
     * Angular's own i18n leaves the interpolations in the template, and the message is the element's content
     * as `ng extract-i18n` reads it: a text whose whitespace Angular would rewrite — a line break, two
     * blanks, blanks around it in its tag — is left to `ng extract-i18n`, which writes it as it reads it.
     */
    override fun refusal(element: PsiElement): String? {
        val text = MarkupTexts.of(element, AngularMarkup) ?: return null
        val content = nativeContent(element, text) ?: return null
        return PluginBundle.message("extract.angular.i18n.interpolation").takeIf { nativeMessage(content) == null }
    }

    /** None with Angular's own i18n: the interpolations stay in the template, nothing is passed to a call. */
    override fun variables(element: PsiElement): List<MessageVariable> {
        val content = MarkupTexts.of(element, AngularMarkup) as? MarkupText.Content ?: return emptyList()
        return if (nativeContent(element, content) != null) emptyList() else content.variables
    }

    /** [text] when it is a content with interpolations marked for Angular's own i18n; else null. */
    private fun nativeContent(element: PsiElement, text: MarkupText): MarkupText.Content? =
        (text as? MarkupText.Content)?.takeIf { it.variables.isNotEmpty() && marksNatively(element) }

    /**
     * The message Angular reads from [content] once marked, its interpolations as written —
     * `Hello {{ user.name }}!` — or null when Angular would read it otherwise than the template writes it:
     * whitespace it collapses (a line break, a tab, two blanks) or blanks between the text and its tag.
     */
    private fun nativeMessage(content: MarkupText.Content): String? {
        val fileText = content.element.containingFile.text
        val raw = content.range.substring(fileText)
        if (raw.any { it.isWhitespace() && it != ' ' } || "  " in raw || !fillsItsTag(content)) return null
        return StringUtil.unescapeXmlEntities(raw)
    }

    /**
     * Whether the marked element holds [content] and nothing else: the tag `i18n` goes on, from its `>` to
     * its `</`; true when the text is wrapped in an `<ng-container>` of its own ([AngularI18nMarkers.of]).
     */
    private fun fillsItsTag(content: MarkupText.Content): Boolean {
        val tag = content.element.parent as? XmlTag ?: return true
        val tagEnd = tag.node?.findChildByType(XmlTokenType.XML_TAG_END)?.startOffset ?: return true
        if (tagEnd > content.range.startOffset) return true
        val endTag = tag.node?.findChildByType(XmlTokenType.XML_END_TAG_START)?.startOffset ?: return false
        return tagEnd + 1 == content.range.startOffset && endTag == content.range.endOffset
    }

    override fun template(element: PsiElement): (argument: String) -> String {
        val call = call(element)
        val variables = variables(element)
        return { call(it, variables) }
    }

    override fun call(element: PsiElement): (argument: String, variables: List<MessageVariable>) -> String {
        val pipe = pipeOf(element)
        val escape = hostEscaper(element)
        if (marksNatively(element)) {
            val marking = MarkupTexts.of(element, AngularMarkup)?.let(AngularI18nMarkers::of)
            if (marking != null) return { argument, _ -> marking.write(AngularI18nMarkers.idOf(argument), escape) }
        }
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

    private fun marksNatively(element: PsiElement): Boolean = AngularI18nMarkers.applies(element, ModulePresets.presetOf(element))

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
