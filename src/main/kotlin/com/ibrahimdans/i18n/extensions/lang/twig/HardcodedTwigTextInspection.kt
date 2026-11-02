package com.ibrahimdans.i18n.extensions.lang.twig

import com.ibrahimdans.i18n.extensions.lang.js.HardcodedMarkupTextInspection
import com.ibrahimdans.i18n.extensions.lang.js.MarkupDialect
import com.ibrahimdans.i18n.extensions.lang.js.MarkupText
import com.ibrahimdans.i18n.extensions.lang.js.MarkupTexts
import com.ibrahimdans.i18n.extensions.technology.symfony.SymfonyCatalogue
import com.ibrahimdans.i18n.extensions.technology.symfony.SymfonyProject
import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.templateLanguages.OuterLanguageElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText
import com.jetbrains.twig.TwigLanguage

/**
 * Flags a text written as is in the HTML of a Twig template of a Symfony project — `<button>Save</button>`,
 * `<input placeholder="Name">` — with a quick fix writing `{{ 'key'|trans }}` ([TwigMarkupTextExtractor]).
 * The rules are those of [MarkupTexts]: an echo of a plain value — `Hello {{ user.name }}!` — is a
 * variable of the message, written `%name%` as Symfony does. A text any other Twig construct splits —
 * a tag `{% if %}`, a `{% trans %}`, a comment, an echo of a filter or a call — is left alone.
 */
class HardcodedTwigTextInspection :
    HardcodedMarkupTextInspection("I18nHardcodedTwigText", "inspection.hardcoded.jsx.text.message", TwigMarkup, TwigMarkupTextExtractor())

/** The HTML of a Twig template: its echoes and tags are Twig, left out of the markup as template elements of the texts they split. */
internal object TwigMarkup : MarkupDialect {

    /** An echo, `{{ name }}` or `{{- name -}}`, with the blanks the template writes after it. */
    private val ECHO = Regex("""\{\{-?(.*?)-?}}\s*""", RegexOption.DOT_MATCHES_ALL)

    /** A variable or an attribute of one: `name`, `user.name`. */
    private val PLAIN_VALUE = Regex("""[A-Za-z_]\w*(\.[A-Za-z_]\w*)*""")

    override fun applies(element: PsiElement): Boolean =
        element.containingFile?.viewProvider?.baseLanguage == TwigLanguage.INSTANCE &&
            SymfonyProject.isSymfony(element.project) && !holdsTwig(element)

    override fun isInterpolation(element: PsiElement): Boolean = isInnerInterpolation(element)

    /** An echo of a plain value: `{{ user.name }}`. */
    override fun isInnerInterpolation(element: PsiElement): Boolean =
        element is OuterLanguageElement && expressionOf(element)?.let(PLAIN_VALUE::matches) == true

    override fun expressionOf(element: PsiElement): String? = ECHO.matchEntire(element.text)?.groupValues?.get(1)?.trim()

    /** The last segment of the value: `name` for `user.name`. */
    override fun variableNameOf(expression: String): String? =
        if (PLAIN_VALUE.matches(expression)) expression.substringAfterLast('.') else null

    /** Symfony's: `%name%`. */
    override fun placeholder(name: String): String = "%$name%"

    /** Whether Twig writes into the texts of [element]'s tag anything but an echo of a value, or anything into the attribute value [element] is. */
    private fun holdsTwig(element: PsiElement): Boolean = when (element) {
        is XmlText -> PsiTreeUtil.getChildrenOfTypeAsList(element.parent ?: return false, XmlText::class.java)
            .any { text -> outers(text).any { !isInnerInterpolation(it) } }
        is XmlAttributeValue -> outers(element).isNotEmpty()
        else -> false
    }

    private fun outers(element: PsiElement): List<OuterLanguageElement> =
        PsiTreeUtil.getChildrenOfTypeAsList(element, OuterLanguageElement::class.java)
}

/**
 * Extracts a text of a Twig template — one [HardcodedTwigTextInspection] reports — into Symfony's filter:
 *  - `<p>Save</p>` becomes `<p>{{ 'save'|trans }}</p>`;
 *  - `<p>Hello {{ user.name }}!</p>` becomes `<p>{{ 'hello'|trans({'%name%': user.name}) }}</p>`, its message `Hello %name%!`;
 *  - `placeholder="Name"` becomes `placeholder="{{ 'name'|trans }}"`.
 *
 * The key goes to the template's domain — its `{% trans_default_domain %}`, else `messages` — which the filter reads without naming it.
 */
internal class TwigMarkupTextExtractor : TranslationExtractor {

    override fun canExtract(element: PsiElement): Boolean = MarkupTexts.of(element, TwigMarkup) != null

    override fun isExtracted(element: PsiElement): Boolean = false

    override fun text(element: PsiElement): String = MarkupTexts.of(element, TwigMarkup)?.text.orEmpty()

    override fun textRange(element: PsiElement): TextRange = MarkupTexts.of(element, TwigMarkup)?.range ?: element.textRange

    override fun variables(element: PsiElement): List<MessageVariable> =
        (MarkupTexts.of(element, TwigMarkup) as? MarkupText.Content)?.variables.orEmpty()

    override fun template(element: PsiElement): (argument: String) -> String {
        val call = call(element)
        val variables = variables(element)
        return { call(it, variables) }
    }

    override fun call(element: PsiElement): (argument: String, variables: List<MessageVariable>) -> String {
        val attribute = (MarkupTexts.of(element, TwigMarkup) as? MarkupText.Attribute)?.attribute
        if (attribute != null) return { argument, _ -> "${attribute.name}=\"{{ $argument|trans }}\"" }
        return { argument, variables ->
            if (variables.isEmpty()) "{{ $argument|trans }}" else "{{ $argument|trans(${parameters(variables)}) }}"
        }
    }

    override fun scopeNamespaces(element: PsiElement): List<String> {
        val twig = element.containingFile?.viewProvider?.getPsi(TwigLanguage.INSTANCE)
        return listOf(TwigTrans.defaultDomain(twig) ?: SymfonyCatalogue.DEFAULT_DOMAIN)
    }

    /** Symfony's parameters: `{'%name%': user.name}`. */
    private fun parameters(variables: List<MessageVariable>): String =
        variables.joinToString(", ", "{", "}") { "'${it.placeholder}': ${it.expression}" }
}
