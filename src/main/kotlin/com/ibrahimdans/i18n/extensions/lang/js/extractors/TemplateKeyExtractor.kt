package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.lang.js.isDirectOrConfiguredCall
import com.ibrahimdans.i18n.extensions.lang.js.jsRuleDecision
import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.rules.RuleDecision
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.KeyElementType
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.psi.PsiElement

/**
 * A template's static parts as [KeyElementType.LITERAL] and its interpolations as
 * [KeyElementType.TEMPLATE], which the key parser reads as wildcards.
 */
internal fun templateKeyElements(template: JSStringTemplateExpression): List<KeyElement> =
    template.node.getChildren(null).map {
        KeyElement(it.text, if (it is JSExpression) KeyElementType.TEMPLATE else KeyElementType.LITERAL)
    }

/** Every hook gives a bare `t`, and vue-i18n's `useI18n()` gives it without publishing it. */
private const val BARE_T = "t"

/**
 * True when [argument] is the first argument of a translation call as the annotator reads one: a
 * name the technologies publish, restricted to the preset of [element]'s module, a bare `t`, or a
 * call a rule includes, under the qualifier and import rules of [isDirectOrConfiguredCall].
 * [argument] is [element] itself, or the ternary holding it in a branch.
 *
 * The template keys and the folding used to know `t`, `$t` and `i18n.t` only: `` $tc(`a.${x}`) ``
 * was never checked and `i18n._('key')` never folded, while the annotator read both.
 */
internal fun isTranslationCallArgument(element: PsiElement, argument: PsiElement = element): Boolean {
    val arguments = argument.parent as? JSArgumentList ?: return false
    if (arguments.arguments.firstOrNull() !== argument) return false
    val callee = (arguments.parent as? JSCallExpression)?.methodExpression as? JSReferenceExpression ?: return false
    val name = callee.referenceName ?: return false
    val published = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
    val included = jsRuleDecision(element) == RuleDecision.INCLUDE
    // The cheap test first: most first arguments of a file are passed to no translation call, and the
    // module's preset is only looked up for the ones that are.
    if (!included && name != BARE_T && published.none { it.substringAfterLast('.') == name }) return false
    val names = ModulePresets.restrict(published, ModulePresets.presetOf(element)) + BARE_T +
        listOfNotNull(callee.text.takeIf { included })
    if (names.none { it.substringAfterLast('.') == name }) return false
    return isDirectOrConfiguredCall(element, names)
}

/**
 * Extracts i18n key from JS string template expression
 */
class TemplateKeyExtractor : KeyExtractor {

    /** Checks if the template literal is used as an i18n function argument, or has a namespace separator in its static parts */
    private fun isI18nContext(element: PsiElement): Boolean {
        if (isTranslationCallArgument(element)) return true
        // Fallback: check that static parts of the template contain ':' (namespace separator hint)
        val staticText = element.node.getChildren(null)
            .filter { it !is JSExpression }
            .joinToString("") { it.text }
        return staticText.contains(":")
    }

    override fun canExtract(element: PsiElement): Boolean =
        element is JSStringTemplateExpression && isI18nContext(element)

    override fun extract(element: PsiElement): RawKey =
        RawKey(
            templateKeyElements(element as JSStringTemplateExpression),
            OptionsExtractor.extractNamespaces(element),
            context = OptionsExtractor.extractContext(element),
        )
}
