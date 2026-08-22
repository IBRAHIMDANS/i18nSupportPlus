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
    override fun isExtracted(element: PsiElement): Boolean =
        translationFunctionNames(element).any {
            PhpPatternsExt.phpArgument(it, 0).accepts(getTextElement(element.parent))
        }
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
     */
    private fun translationFunctionNames(element: PsiElement): List<String> {
        val config = Settings.getInstance(element.project).config()
        if (!config.gettext) return listOf("t")
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