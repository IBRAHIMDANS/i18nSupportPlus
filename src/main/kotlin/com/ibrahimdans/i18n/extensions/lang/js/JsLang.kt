package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.extensions.lang.js.extractors.*
import com.ibrahimdans.i18n.plugin.factory.FoldingProvider
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.rules.RuleCalls
import com.ibrahimdans.i18n.plugin.rules.RuleDecision
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.ibrahimdans.i18n.plugin.utils.type
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.patterns.JSPatterns
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSThisExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

open class JsLang : Lang {

    companion object {
        private val REACT_INTL_EXTRACTOR = ReactIntlExtractor()

        /**
         * Extractors that recognise their own call syntax, so they answer before the
         * generic `translationFunctionNames` filtering instead of after it.
         *
         * Without that short-circuit they can never fire:
         *  - react-intl passes the key inside a descriptor (`formatMessage({ id: 'key' })`),
         *    where the generic extractors would happily match any string of the object,
         *    `defaultMessage` included;
         *  - a react-intl catalogue declares its descriptors under `defineMessages({ … })`,
         *    a call no `translationFunctionNames` entry ever names;
         *  - ngx-translate calls are always qualified (`translate.instant('key')`), and
         *    [isDirectOrConfiguredCall] rejects qualified calls by design;
         *  - svelte-i18n's `_` / `$_` carry no namespace options, so matching the call
         *    itself is more precise than matching a bare string literal.
         */
        private val SYNTAX_OWNED_EXTRACTORS: List<KeyExtractor> = listOf(
            REACT_INTL_EXTRACTOR,
            DefineMessagesExtractor(),
            NgxTranslateExtractor(),
            SvelteI18nExtractor(),
        )
    }

    /** Extractors owning their syntax; JSX adds the tag- and attribute-based ones. */
    protected open fun syntaxOwnedExtractors(): List<KeyExtractor> = SYNTAX_OWNED_EXTRACTORS

    /**
     * The syntax-owned extractors of the frameworks a module preset leaves active for [element].
     * An extractor recognises one framework's syntax, so a preset naming another one silences it.
     */
    private fun activeExtractors(preset: String?): List<KeyExtractor> =
        syntaxOwnedExtractors().filter { ModulePresets.allows(preset, frameworkOf(it)) }

    override fun canExtractKey(element: PsiElement, translationFunctionNames: List<String>): Boolean {
        val decision = jsRuleDecision(element)
        if (decision == RuleDecision.EXCLUDE) return false
        // A module preset keeps its framework's calls only; a rule including a call still wins.
        val preset = ModulePresets.presetOf(element)
        if (activeExtractors(preset).any { it.canExtract(element) }) return true
        // Claiming `id` is not enough: the descriptor's other properties would still reach
        // the generic path below, which matches any string literal of a first-argument
        // object — reporting `defaultMessage` as an unresolved key.
        if (REACT_INTL_EXTRACTOR.isInsideMessageDescriptor(element)) return false
        // A call a rule includes is matched by its method name, like a published one.
        val presetNames = ModulePresets.restrict(translationFunctionNames, preset)
        val names = if (decision == RuleDecision.INCLUDE) {
            presetNames + listOfNotNull(calleeOf(element)?.substringAfterLast('.'))
        } else presetNames
        return names.any { t ->
            JSPatterns.jsArgument(t, 0).let { pattern ->
                pattern.accepts(element) ||
                    (!isNestedInsideTemplateExpression(element) &&
                        !isInsideConditionalCondition(element) &&
                        pattern.accepts(PsiTreeUtil.findFirstParent(element) { it.parent?.type() == "JS:ARGUMENT_LIST" }))
            }
        } && isDirectOrConfiguredCall(element, presetNames)
          && extractRawKey(element) != null
    }

    private fun isNestedInsideTemplateExpression(element: PsiElement): Boolean {
        var current = element.parent
        while (current != null && current.type() != "JS:ARGUMENT_LIST") {
            if (current.type() == "JS:STRING_TEMPLATE_EXPRESSION") return true
            current = current.parent
        }
        return false
    }

    /**
     * Returns true if [element] is inside the condition part of a ternary expression
     * (i.e. before the '?'), not in a value branch.
     * Prevents false positives like 'remove' in: t(x === 'remove' ? 'key1' : 'key2')
     */
    private fun isInsideConditionalCondition(element: PsiElement): Boolean {
        var current = element.parent
        while (current != null && current.type() != "JS:ARGUMENT_LIST") {
            if (current.type() == "JS:CONDITIONAL_EXPRESSION") {
                val conditionNode = current.children.firstOrNull() ?: return false
                if (PsiTreeUtil.isAncestor(conditionNode, element, false)) return true
            }
            current = current.parent
        }
        return false
    }

    override fun extractRawKey(element: PsiElement): RawKey? {
        activeExtractors(ModulePresets.presetOf(element)).firstOrNull { it.canExtract(element) }?.let { return it.extract(element) }
        return listOf(
                    ReactUseTranslationHookExtractor(),
                    TemplateKeyExtractor(),
                    LiteralKeyExtractor(),
                    StringLiteralKeyExtractor(),
                    XmlAttributeKeyExtractor()
            ).find {it.canExtract(element)}?.extract(element)
        }

    override fun foldingProvider(): FoldingProvider = JsFoldingProvider()

    override fun translationExtractor(): TranslationExtractor = JsTranslationExtractor()

    override fun resolveLiteral(entry: PsiElement): PsiElement? {
        val typeName = entry.node.elementType.toString()
        return if (setOf("JS:STRING_LITERAL", "JS:STRING_TEMPLATE_EXPRESSION").contains (typeName)) entry
            else if (typeName == "JS:STRING_TEMPLATE_PART") entry.parent
            else null
    }
}

/** The framework whose syntax [extractor] recognises, as a module preset names it. */
private fun frameworkOf(extractor: KeyExtractor): String? = when (extractor) {
    is ReactIntlExtractor, is DefineMessagesExtractor, is FormattedMessageExtractor -> "react-intl"
    is NgxTranslateExtractor, is NgxTranslatePipeExtractor -> "ngx-translate"
    is SvelteI18nExtractor -> "svelte-i18n"
    is LinguiTransKeyExtractor -> "lingui"
    else -> null
}

/**
 * False when the call holding [element] is qualified by something other than `this` and its whole
 * method text is not one of [translationFunctionNames]: `toast.t('key')` stays out while `this.$t`,
 * `i18n._` or `props.t` — once published — get through.
 *
 * The one copy the JS dialect's annotation, references and folding share. Each used to carry its
 * own, which is how a name accepted in one place could be rejected in another.
 */
internal fun isDirectOrConfiguredCall(element: PsiElement, translationFunctionNames: Collection<String>): Boolean {
    when (jsRuleDecision(element)) {
        RuleDecision.INCLUDE -> return true
        RuleDecision.EXCLUDE -> return false
        RuleDecision.NONE -> {}
    }
    val callExpr = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java) ?: return true
    val refExpr = callExpr.methodExpression as? JSReferenceExpression ?: return true
    val qualifier = refExpr.qualifier ?: return importsTheFrameworkOf(refExpr.text, element)
    if (qualifier is JSThisExpression) return true
    return refExpr.text in translationFunctionNames
}

/**
 * Bare function names common enough outside i18n to be claimed only when the file imports the
 * framework publishing them. Every technology's names apply to every project, so lodash's
 * `_('…')` or a local `msg('…')` used to be annotated as unresolved keys.
 */
private val IMPORT_GATED_NAMES: Map<String, List<String>> = mapOf(
    "_" to listOf("svelte-i18n"),
    "msg" to listOf("@lingui/"),
)

/**
 * True unless [name] is import-gated and the file holding [element] — the host file, for a
 * fragment injected into a Svelte or Vue component — imports none of its packages.
 */
internal fun importsTheFrameworkOf(name: String, element: PsiElement): Boolean {
    val packages = IMPORT_GATED_NAMES[name] ?: return true
    val file = InjectedLanguageManager.getInstance(element.project).getTopLevelFile(element) ?: element.containingFile ?: return false
    val text = file.text
    return packages.any { pkg -> Regex("""(from|require\(|import)\s*['"]${Regex.escape(pkg)}""").containsMatchIn(text) }
}

/** The called function as written — `translate`, `i18n.t` — for the call holding [element], or null. */
internal fun calleeOf(element: PsiElement): String? =
    (PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java)?.methodExpression as? JSReferenceExpression)?.text

/**
 * What the *Key assistance rules* decide about the call holding [element]: an including rule makes
 * it a translation call even though no framework publishes its name, an excluding one takes it out
 * even though one does.
 */
internal fun jsRuleDecision(element: PsiElement): RuleDecision {
    val callee = calleeOf(element) ?: return RuleDecision.NONE
    return RuleCalls.decide(element, "js", callee, ::importsOf)
}

private val IMPORT_SPECIFIER = Regex("""(?:from|require\(|import)\s*['"]([^'"]+)['"]""")

/** The module specifiers [file] imports or requires. */
private fun importsOf(file: PsiFile): Set<String> =
    IMPORT_SPECIFIER.findAll(file.text).mapTo(mutableSetOf()) { it.groupValues[1] }
