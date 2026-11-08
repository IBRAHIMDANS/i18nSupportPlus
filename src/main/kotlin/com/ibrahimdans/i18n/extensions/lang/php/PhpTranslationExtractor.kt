package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.default
import com.ibrahimdans.i18n.plugin.utils.type
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.ibrahimdans.i18n.plugin.utils.whenMatches
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

internal class PhpTranslationExtractor: TranslationExtractor {
    override fun canExtract(element: PsiElement): Boolean =
        (element.isPhpStringLiteral() || element.isBorderToken())
    override fun isExtracted(element: PsiElement): Boolean {
        val argument = getTextElement(element.parent)
        return readFunctionNames(element).any { PhpPatternsExt.phpArgument(it, 0).accepts(argument) } ||
            pluralCalls(element).any { PhpPatternsExt.phpArgument(it, 1).accepts(argument) }
    }

    /**
     * The calls whose first argument is already a key: the ones written by [template], plus, in GetText
     * mode, the ones [phpTranslationFunctionNames] reads (`ngettext` among them), so a string the plugin
     * reads as a key is not offered for extraction again.
     */
    private fun readFunctionNames(element: PsiElement): List<String> {
        val config = Settings.getInstance(element.project).config()
        val written = translationFunctionNames(element)
        if (!config.gettext) return written
        return (written + phpTranslationFunctionNames(element.project, config, emptyList())).distinct()
    }

    /**
     * GetText's plural calls in GetText mode: their second argument is the entry's `msgid_plural`, part of
     * the key in the first argument, never a text to extract on its own.
     */
    private fun pluralCalls(element: PsiElement): List<String> =
        if (Settings.getInstance(element.project).config().gettext) GETTEXT_PLURAL_CALLS else emptyList()

    override fun template(element: PsiElement): (argument: String) -> String {
        val function = translationFunctionNames(element).first()
        return {"$function($it)"}
    }

    /**
     * The functions an extracted key may be wrapped in, most preferred first.
     *
     * A GetText project calls `gettext('…')` (or `_`, `__`), never `t('…')`: the msgid *is* the
     * key, and the call is what the C-style tooling scans for. Writing `t(…)` there produces code
     * the project's own extractor cannot see. [com.ibrahimdans.i18n.extensions.lang.php.PhpLang]
     * and `PhpReferenceAssistant` already read the aliases to *recognise* such calls — only
     * writing them was left behind, so the plugin could not re-read what it had just extracted.
     * A Laravel project calls `__('…')` the same way: Laravel has no `t()`.
     */
    private fun translationFunctionNames(element: PsiElement): List<String> {
        val config = Settings.getInstance(element.project).config()
        if (!config.gettext) return if (LaravelProject.isLaravel(element.project)) listOf("__") else listOf("t")
        return config.gettextAliases
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("t") }
    }
    override fun text(element: PsiElement): String = getTextElement(element).text.unQuote()
    override fun textRange(element: PsiElement): TextRange = getTextElement(element).parent.textRange
    private fun getTextElement(element: PsiElement) =
        element.whenMatches {it.isBorderToken()}?.prevSibling.default(element)
    private fun PsiElement.isBorderToken(): Boolean = listOf("right double quote", "right single quote").contains(this.type())
    private fun PsiElement.isPhpStringLiteral(): Boolean = listOf("double quoted string", "single quoted string").contains(this.type())
}