package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.extensions.lang.php.extractors.PhpStringLiteralKeyExtractor
import com.ibrahimdans.i18n.plugin.factory.FoldingProvider
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.rules.RuleCalls
import com.ibrahimdans.i18n.plugin.rules.RuleDecision
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.ParameterList

class PhpLang: Lang {
    override fun canExtractKey(element: PsiElement, translationFunctionNames: List<String>): Boolean {
        // The one test only a PHP quoted-string token passes, so it goes first: every Lang is asked of
        // every element of every file, and on a JS element the rest allocated a Config, walked up to
        // the file root and built a pattern per name before this answered no.
        if (extractRawKey(element) == null) return false
        val config = Settings.getInstance(element.project).config()
        val decision = phpRuleDecision(element)
        if (decision == RuleDecision.EXCLUDE) return false
        if (isLaravelTranslatorCall(element) && LaravelProject.isLaravel(element.project)) return true
        val publishedNames = phpTranslationFunctionNames(element.project, config, translationFunctionNames)
        val functionNames = if (decision == RuleDecision.INCLUDE) publishedNames + listOfNotNull(phpCalleeOf(element)) else publishedNames
        // The annotator receives leaf tokens (e.g. "double quoted string"), but phpArgument()
        // operates on PhpExpression nodes. Walk up to find the ancestor that is a direct child
        // of the ParameterList (handles both t("key") and t(cond ? "key" : "other")).
        val argumentAncestor = generateSequence(element.parent) { it.parent }
            .firstOrNull { it.parent is ParameterList }
        return functionNames.any { name ->
            val pattern = PhpPatternsExt.phpArgument(name, 0)
            pattern.accepts(element) || pattern.accepts(argumentAncestor)
        }
    }

    override fun extractRawKey(element: PsiElement): RawKey? {
        val extractor = PhpStringLiteralKeyExtractor()
        return if (extractor.canExtract(element)) extractor.extract(element) else null
    }

    override fun foldingProvider(): FoldingProvider = PhpFoldingProvider()

    override fun translationExtractor(): TranslationExtractor = PhpTranslationExtractor()

    override fun resolveLiteral(entry: PsiElement): PsiElement? {
        val typeName = entry.node.elementType.toString()
        return if (typeName == "single quoted string") entry else null
    }
}

/**
 * The functions a PHP key may be passed to: the GetText aliases in GetText mode, otherwise the
 * names the technologies publish that PHP can call — plus Laravel's helpers in a Laravel project.
 */
internal fun phpTranslationFunctionNames(project: Project, config: Config, technologyNames: List<String>): List<String> {
    if (config.gettext) return config.gettextAliases.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val published = technologyNames.filter { PhpPatternsExt.isValidPhpFunctionName(it) }
    return if (LaravelProject.isLaravel(project)) published + LARAVEL_TRANSLATION_FUNCTIONS else published
}

/** The name of the function or method called with [element] among its arguments, or null. */
internal fun phpCalleeOf(element: PsiElement): String? =
    PsiTreeUtil.getParentOfType(element, FunctionReference::class.java)?.name

/**
 * What the *Key assistance rules* decide about the call holding [element]: an including rule makes
 * `__('key')` a translation call, an excluding one takes a published call out.
 */
internal fun phpRuleDecision(element: PsiElement): RuleDecision {
    val callee = phpCalleeOf(element) ?: return RuleDecision.NONE
    return RuleCalls.decide(element, "php", callee, ::useStatementsOf)
}

private val USE_STATEMENT = Regex("""(?m)^\s*use\s+\\?([\w\\]+)""")

/** The namespaces and classes [file] imports with `use`. */
private fun useStatementsOf(file: PsiFile): Set<String> =
    USE_STATEMENT.findAll(file.text).mapTo(mutableSetOf()) { it.groupValues[1] }
