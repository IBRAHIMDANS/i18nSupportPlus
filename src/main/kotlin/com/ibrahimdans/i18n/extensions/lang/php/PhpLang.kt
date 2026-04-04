package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.extensions.lang.php.extractors.PhpStringLiteralKeyExtractor
import com.ibrahimdans.i18n.plugin.factory.FoldingProvider
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.intellij.psi.PsiElement
import com.jetbrains.php.lang.psi.elements.ParameterList

class PhpLang: Lang {
    override fun canExtractKey(element: PsiElement, translationFunctionNames: List<String>): Boolean {
        val config = Settings.getInstance(element.project).config()
        val functionNames = if (config.gettext) {
            config.gettextAliases.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            translationFunctionNames
        }
        // The annotator receives leaf tokens (e.g. "double quoted string"), but phpArgument()
        // operates on PhpExpression nodes. Walk up to find the ancestor that is a direct child
        // of the ParameterList (handles both t("key") and t(cond ? "key" : "other")).
        val argumentAncestor = generateSequence(element.parent) { it.parent }
            .firstOrNull { it.parent is ParameterList }
        return functionNames.any { name ->
            val pattern = PhpPatternsExt.phpArgument(name, 0)
            pattern.accepts(element) || pattern.accepts(argumentAncestor)
        } && extractRawKey(element) != null
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

